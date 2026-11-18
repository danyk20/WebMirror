package app.webmirror.android.net.acme

import java.io.IOException
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AcmeDirectories {
    const val PRODUCTION = "https://acme-v02.api.letsencrypt.org/directory"
    const val STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory"
}

/** What the caller must persist after success: [accountKey] (reuse on renewal), subdomain, token, [notAfterMs]. */
sealed class IssueResult {
    class Success(
        val fqdn: String,
        /** PKCS#12 bytes; import with TlsStore.import(context, pkcs12, password.toCharArray()). */
        val pkcs12: ByteArray,
        val password: String,
        val notBeforeMs: Long,
        val notAfterMs: Long,
        val accountKey: AcmeAccountKey,
    ) : IssueResult()

    sealed class Failure(val message: String) : IssueResult()
    class InvalidInput(message: String) : Failure(message)
    class TermsNotAccepted : Failure("The Let's Encrypt terms of service must be accepted first.")
    class DuckDnsRejected(message: String) : Failure(message)
    class NetworkError(message: String) : Failure(message)
    class Rejected(message: String) : Failure(message)
    class RateLimited(val retryAfter: String?, message: String) : Failure(message)
    class Timeout(message: String) : Failure(message)
    class Cancelled : Failure("Cancelled.")
}

class IssueRequest(
    /** "myname", "myname.duckdns.org" or "https://myname.duckdns.org/". */
    val subdomain: String,
    val token: String,
    /** The address the name should resolve to (the phone's virtual address). */
    val virtualIp: String,
    val agreedToTerms: Boolean,
    /** Existing account key from an earlier issuance, or null to create an account. */
    val accountKey: AcmeAccountKey? = null,
)

object CertInput {
    private val SUB = Regex("^[a-z0-9-]{1,63}$")
    private val TOKEN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Returns the bare subdomain, or null if invalid. */
    fun normalizeSubdomain(input: String): String? {
        var s = input.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore(':')
        s = s.removeSuffix(".duckdns.org")
        return if (SUB.matches(s)) s else null
    }

    fun isValidToken(token: String): Boolean = TOKEN.matches(token.trim())
}

/** True when less than a third of the certificate lifetime is left (90-, 64- and 45-day certificates alike). */
fun shouldRenew(notAfterMs: Long, notBeforeMs: Long, nowMs: Long): Boolean {
    val lifetime = notAfterMs - notBeforeMs
    return notAfterMs - nowMs < lifetime / 3
}

/**
 * Blocking DuckDNS + Let's Encrypt (dns-01) certificate issuer. Run it on a background thread;
 * interrupt the thread (or set [isCancelled]) to cancel.
 */
class CertIssuer(
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val directoryUrl: String = AcmeDirectories.PRODUCTION,
    private val progress: (String) -> Unit = {},
    private val isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val propagationTimeoutMs: Long = 120_000,
    private val pollTimeoutMs: Long = 180_000,
) {
    private class Abort(val result: IssueResult.Failure) : Exception(null, null, false, false)

    private var nonce: String? = null
    private var token = ""

    fun issue(req: IssueRequest): IssueResult {
        token = req.token.trim()
        try {
            val sub = CertInput.normalizeSubdomain(req.subdomain)
                ?: return IssueResult.InvalidInput("The name must be letters, digits and dashes only, like myname or myname.duckdns.org.")
            if (!CertInput.isValidToken(token)) {
                return IssueResult.InvalidInput("The DuckDNS token looks wrong: it is 36 characters like 123e4567-e89b-12d3-a456-426614174000.")
            }
            if (!req.agreedToTerms) return IssueResult.TermsNotAccepted()
            return run(req, sub)
        } catch (a: Abort) {
            return a.result
        } catch (e: InterruptedException) {
            return IssueResult.Cancelled()
        } catch (e: IOException) {
            return IssueResult.NetworkError(netMessage(e))
        } catch (e: Exception) {
            return IssueResult.Rejected("Unexpected response: ${scrub(e.message ?: e.javaClass.simpleName)}")
        }
    }

    private fun run(req: IssueRequest, sub: String): IssueResult {
        val fqdn = "$sub.duckdns.org"
        val txtName = "_acme-challenge.$fqdn"
        var txtSet = false
        try {
            step("Setting $fqdn to ${req.virtualIp}")
            duck("domains=$sub&token=$token&ip=${req.virtualIp}")

            step("Contacting Let's Encrypt")
            val dir = getJson(directoryUrl)
            val newNonce = dir.str("newNonce"); val newAccount = dir.str("newAccount"); val newOrder = dir.str("newOrder")
            nonce = null
            fetchNonce(newNonce)

            val accountKey = req.accountKey ?: AcmeAccountKey.from(AcmeCrypto.generateEcKey())
            val key = accountKey.toKeyPair()

            step("Registering the Let's Encrypt account")
            val acct = post(key, null, newAccount, MiniJson.write(linkedMapOf("termsOfServiceAgreed" to true)), newNonce)
            val kid = acct.headers["location"] ?: fail("Let's Encrypt did not return an account address.")

            step("Ordering a certificate for $fqdn")
            val orderResp = post(key, kid, newOrder, MiniJson.write(linkedMapOf(
                "identifiers" to listOf(linkedMapOf("type" to "dns", "value" to fqdn)))), newNonce)
            val orderUrl = orderResp.headers["location"] ?: fail("Let's Encrypt did not return an order address.")
            val order = MiniJson.parseObject(orderResp.text)
            val authzUrl = (order["authorizations"] as? List<*>)?.firstOrNull() as? String ?: fail("The order has no authorization.")
            val finalizeUrl = order.str("finalize")

            val authz = MiniJson.parseObject(post(key, kid, authzUrl, "", newNonce).text)
            if (authz["status"] != "valid") {
                @Suppress("UNCHECKED_CAST")
                val ch = (authz["challenges"] as? List<Map<String, Any?>>)?.firstOrNull { it["type"] == "dns-01" }
                    ?: fail("Let's Encrypt offered no DNS check.")
                val keyAuth = ch.str("token") + "." + AcmeCrypto.thumbprint(key.public as java.security.interfaces.ECPublicKey)
                val txt = AcmeCrypto.b64url(AcmeCrypto.sha256(keyAuth.toByteArray(Charsets.US_ASCII)))

                step("Publishing the check record at DuckDNS")
                txtSet = true
                duck("domains=$sub&token=$token&txt=$txt")
                waitForTxt(txtName, txt)

                step("Let's Encrypt is checking…")
                post(key, kid, ch.str("url"), "{}", newNonce)
                pollAuthz(key, kid, authzUrl, newNonce)
            }

            step("Creating the certificate")
            val certKey = AcmeCrypto.generateEcKey()
            val csr = Der.csr(fqdn, certKey.public, certKey.private)
            val fin = MiniJson.parseObject(post(key, kid, finalizeUrl,
                MiniJson.write(linkedMapOf("csr" to AcmeCrypto.b64url(csr))), newNonce).text)
            val certUrl = pollOrder(key, kid, orderUrl, fin, newNonce)

            val pem = post(key, kid, certUrl, "", newNonce, mapOf("Accept" to "application/pem-certificate-chain")).text
            val (p12, password, leaf) = buildPkcs12(certKey, pem)
            step("Certificate received, valid until ${fmt(leaf.notAfter)}")
            return IssueResult.Success(fqdn, p12, password, leaf.notBefore.time, leaf.notAfter.time, accountKey)
        } finally {
            if (txtSet) {
                try { duckBestEffort("domains=$sub&token=$token&txt=&clear=true") } catch (_: Exception) {}
            }
        }
    }

    // ---- DuckDNS / DNS ----

    private fun duck(query: String) {
        check()
        val r = transport.request("GET", "https://www.duckdns.org/update?$query")
        if (r.status != 200 || !r.text.trim().startsWith("OK")) {
            throw Abort(IssueResult.DuckDnsRejected("DuckDNS refused the update: check that the name and the token belong together."))
        }
    }

    private fun duckBestEffort(query: String) {
        if (Thread.currentThread().isInterrupted) return
        transport.request("GET", "https://www.duckdns.org/update?$query")
    }

    private fun waitForTxt(name: String, expected: String) {
        val start = nowMs()
        while (true) {
            check()
            val seen = try { dohHasTxt(name, expected) } catch (e: IOException) { false }
            val elapsed = nowMs() - start
            if (seen) return
            if (elapsed >= propagationTimeoutMs) return // submit anyway
            step("Waiting for DuckDNS to publish the check record (${elapsed / 1000} s)")
            sleepMs(5_000)
        }
    }

    private fun dohHasTxt(name: String, expected: String): Boolean {
        val r = transport.request("GET", "https://cloudflare-dns.com/dns-query?name=$name&type=TXT", mapOf("accept" to "application/dns-json"))
        if (r.status != 200) return false
        val json = try { MiniJson.parseObject(r.text) } catch (e: Exception) { return false }
        val answers = json["Answer"] as? List<*> ?: return false
        return answers.any { (it as? Map<*, *>)?.get("data")?.toString()?.trim('"') == expected }
    }

    // ---- ACME ----

    private fun getJson(url: String): Map<String, Any?> {
        check()
        val r = transport.request("GET", url)
        if (r.status != 200) throw Abort(IssueResult.NetworkError("Let's Encrypt answered with HTTP ${r.status}."))
        return MiniJson.parseObject(r.text)
    }

    private fun fetchNonce(newNonceUrl: String) {
        val r = transport.request("HEAD", newNonceUrl)
        nonce = r.headers["replay-nonce"] ?: fail("Let's Encrypt did not provide a nonce.")
    }

    /** Signed POST; [kid] null means newAccount (jwk). Empty [payload] is POST-as-GET. Retries once on badNonce. */
    private fun post(key: KeyPair, kid: String?, url: String, payload: String, newNonceUrl: String,
                     extra: Map<String, String> = emptyMap()): HttpResponse {
        var attempt = 0
        while (true) {
            check()
            if (nonce == null) fetchNonce(newNonceUrl)
            val used = nonce!!
            nonce = null
            val body = AcmeCrypto.jws(key, url, used, kid, payload)
            val r = transport.request("POST", url, mapOf("Content-Type" to "application/jose+json") + extra, body.toByteArray(Charsets.UTF_8))
            r.headers["replay-nonce"]?.let { nonce = it }
            if (r.status in 200..299) return r
            val problem = try { MiniJson.parseObject(r.text) } catch (e: Exception) { emptyMap() }
            val type = problem["type"] as? String ?: ""
            if (type.endsWith(":badNonce") && attempt == 0) { attempt++; continue }
            throw Abort(problemFailure(r, problem, type))
        }
    }

    private fun problemFailure(r: HttpResponse, problem: Map<String, Any?>, type: String): IssueResult.Failure {
        val detail = problem["detail"] as? String ?: "HTTP ${r.status}"
        val retry = r.headers["retry-after"]
        return if (type.endsWith(":rateLimited") || r.status == 429) {
            IssueResult.RateLimited(retry, "Let's Encrypt rate limit reached" + (retry?.let { " (retry after $it)" } ?: "") + ": $detail")
        } else IssueResult.Rejected("Let's Encrypt rejected the request: $detail")
    }

    private fun pollAuthz(key: KeyPair, kid: String, url: String, nn: String) {
        val start = nowMs()
        while (true) {
            val r = post(key, kid, url, "", nn)
            val a = MiniJson.parseObject(r.text)
            when (a["status"]) {
                "valid" -> return
                "invalid" -> {
                    @Suppress("UNCHECKED_CAST")
                    val err = (a["challenges"] as? List<Map<String, Any?>>)?.firstNotNullOfOrNull { (it["error"] as? Map<*, *>)?.get("detail") as? String }
                    throw Abort(IssueResult.Rejected("Let's Encrypt could not verify the name: ${err ?: "the check failed"}"))
                }
            }
            delay(start, r, "Let's Encrypt did not finish checking in time.")
        }
    }

    private fun pollOrder(key: KeyPair, kid: String, url: String, first: Map<String, Any?>, nn: String): String {
        val start = nowMs()
        var o = first
        while (true) {
            when (o["status"]) {
                "valid" -> return o.str("certificate")
                "invalid" -> throw Abort(IssueResult.Rejected("Let's Encrypt rejected the order."))
            }
            val r = post(key, kid, url, "", nn)
            delay(start, r, "Let's Encrypt did not issue the certificate in time.")
            o = MiniJson.parseObject(r.text)
        }
    }

    private fun delay(start: Long, r: HttpResponse, timeoutMsg: String) {
        if (nowMs() - start >= pollTimeoutMs) throw Abort(IssueResult.Timeout(timeoutMsg))
        val ra = r.headers["retry-after"]?.trim()?.toLongOrNull()
        sleepMs(((ra ?: 2L).coerceIn(1L, 10L)) * 1000)
    }

    // ---- output ----

    private fun buildPkcs12(certKey: KeyPair, pem: String): Triple<ByteArray, String, X509Certificate> {
        val chain = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)))
            .map { it as X509Certificate }
        if (chain.isEmpty()) fail("Let's Encrypt returned no certificate.")
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val password = String(CharArray(24) { chars[rnd.nextInt(chars.length)] })
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("webmirror", certKey.private, password.toCharArray(), chain.toTypedArray())
        val out = ByteArrayOutputStream()
        ks.store(out, password.toCharArray())
        return Triple(out.toByteArray(), password, chain.first())
    }

    // ---- helpers ----

    private fun step(s: String) { check(); progress(s) }

    private fun check() {
        if (isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun fail(msg: String): Nothing = throw Abort(IssueResult.Rejected(msg))

    private fun Map<String, Any?>.str(k: String): String = this[k] as? String ?: fail("Unexpected answer from Let's Encrypt (missing $k).")

    private fun scrub(s: String): String = if (token.isNotEmpty()) s.replace(token, "***") else s

    private fun netMessage(e: IOException): String =
        "Network problem: ${scrub(e.message ?: e.javaClass.simpleName)}. Check the phone's internet connection."

    private fun fmt(d: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
}
