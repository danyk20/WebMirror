package app.webmirror.android

import app.webmirror.android.net.acme.*
import app.webmirror.android.net.acme.MiniJson
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

private const val TOKEN = "123e4567-e89b-12d3-a456-426614174000"
private const val CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBajCCARCgAwIBAgIUHwYVC4rbdyqBDcYjEPuiSdzTXZcwCgYIKoZIzj0EAwIw
GzEZMBcGA1UEAwwQdGVzdC5kdWNrZG5zLm9yZzAeFw0yNjExMTgxMDI3NTJaFw0y
NzAxMTcxMDI3NTJaMBsxGTAXBgNVBAMMEHRlc3QuZHVja2Rucy5vcmcwWTATBgcq
hkjOPQIBBggqhkjOPQMBBwNCAAQTU21VdQmih6mESj7ZR8avkhWC3uejjMTPehcR
/UrPasYvFwlc0SKgv+lsE+GsaSDm+cagtN/XX/ZWK+zmKf/BozIwMDAdBgNVHQ4E
FgQUFs3tv7tvDRMcwiAIpeqZ44Snt+AwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjO
PQQDAgNIADBFAiEA8FmbY4+pdGsSE01DwoTSQL3j2jlb6savvfogRNJXEEQCICeI
qL46v2J/RrtNWX8QyxbllnZwkVVpMLEtilhlVPg4
-----END CERTIFICATE-----
"""

private fun dec(s: String) = Base64.getUrlDecoder().decode(s)

/** In-memory Let's Encrypt + DuckDNS + DoH. */
private class FakeServer : HttpTransport {
    val log = ArrayList<String>()
    var duckAnswer = "OK"
    var badNonceOnce = false
    var rejectChallenge = false
    var nonceCounter = 0
    val validNonces = HashSet<String>()
    var challengePosted = false
    var authzPolls = 0
    var txt: String? = null
    var txtCleared = false
    var challengeToken = "tok123"
    var accountJwk: Map<String, Any?>? = null
    val base = "https://acme.test"

    private fun nonce(): String { val n = "n${nonceCounter++}"; validNonces.add(n); return n }
    private fun r(status: Int, body: String = "", vararg h: Pair<String, String>) =
        HttpResponse(status, mapOf("replay-nonce" to nonce()) + h.toMap(), body.toByteArray())

    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        log.add("$method ${url.substringBefore('?')}")
        if (url.startsWith("https://www.duckdns.org/update")) {
            if (url.contains("txt=&clear=true")) txtCleared = true
            else if (url.contains("&txt=")) txt = url.substringAfter("&txt=")
            return HttpResponse(200, emptyMap(), duckAnswer.toByteArray())
        }
        if (url.startsWith("https://cloudflare-dns.com")) {
            assertEquals("application/dns-json", headers["accept"])
            val answer = if (txt != null) """{"Answer":[{"name":"x","type":16,"data":"\"$txt\""}]}""" else "{}"
            return HttpResponse(200, emptyMap(), answer.toByteArray())
        }
        return when {
            url == "$base/directory" -> HttpResponse(200, emptyMap(), """{"newNonce":"$base/nonce","newAccount":"$base/acct","newOrder":"$base/order"}""".toByteArray())
            url == "$base/nonce" -> r(200)
            method == "POST" -> post(url, body!!)
            else -> HttpResponse(404, emptyMap(), ByteArray(0))
        }
    }

    private fun post(url: String, body: ByteArray): HttpResponse {
        val jws = MiniJson.parseObject(String(body))
        val prot = MiniJson.parseObject(String(dec(jws["protected"] as String)))
        val payload = String(dec(jws["payload"] as String))
        assertEquals("ES256", prot["alg"]); assertEquals(url, prot["url"])
        assertTrue("nonce reuse or unknown", validNonces.remove(prot["nonce"] as String))
        // verify the signature
        @Suppress("UNCHECKED_CAST")
        val jwk = (prot["jwk"] as? Map<String, Any?>) ?: accountJwk!!
        if (prot["jwk"] != null) accountJwk = jwk else assertEquals("$base/acct/1", prot["kid"])
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, dec(jwk["x"] as String)), BigInteger(1, dec(jwk["y"] as String))),
            (java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub); update("${jws["protected"]}.${jws["payload"]}".toByteArray())
            verify(rawToDer(dec(jws["signature"] as String)))
        }
        assertTrue("bad signature", ok)

        if (badNonceOnce) {
            badNonceOnce = false
            return r(400, """{"type":"urn:ietf:params:acme:error:badNonce","detail":"bad"}""")
        }
        return when (url) {
            "$base/acct" -> { assertTrue(payload.contains("\"termsOfServiceAgreed\":true")); r(201, "{}", "location" to "$base/acct/1") }
            "$base/order" -> r(201, """{"status":"pending","authorizations":["$base/authz"],"finalize":"$base/fin"}""", "location" to "$base/order/1")
            "$base/authz" -> {
                if (!challengePosted) r(200, """{"status":"pending","challenges":[{"type":"http-01","url":"$base/c0","token":"x"},{"type":"dns-01","url":"$base/chal","token":"$challengeToken"}]}""")
                else if (rejectChallenge) r(200, """{"status":"invalid","challenges":[{"type":"dns-01","error":{"detail":"no TXT found"}}]}""")
                else if (authzPolls++ < 1) r(200, """{"status":"pending","challenges":[]}""", "retry-after" to "1")
                else r(200, """{"status":"valid","challenges":[]}""")
            }
            "$base/chal" -> { assertEquals("{}", payload); assertNotNull(txt); challengePosted = true; r(200, "{}") }
            "$base/fin" -> {
                assertTrue(txtCleared.not())
                val csr = dec(MiniJson.parseObject(payload)["csr"] as String)
                assertEquals(0x30, csr[0].toInt())
                r(200, """{"status":"processing"}""")
            }
            "$base/order/1" -> r(200, """{"status":"valid","certificate":"$base/cert"}""")
            "$base/cert" -> { assertEquals("", payload); HttpResponse(200, mapOf("replay-nonce" to nonce()), CERT_PEM.toByteArray()) }
            else -> r(404, "{}")
        }
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray { val v = BigInteger(1, b).toByteArray(); return byteArrayOf(2, v.size.toByte()) + v }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

class AcmeTest {
    private fun issuer(server: HttpTransport, steps: MutableList<String> = ArrayList()): CertIssuer {
        var clock = 0L
        return CertIssuer(server, "https://acme.test/directory", { steps.add(it) }, { false }, { clock += it }, { clock })
    }

    private fun req(sub: String = "test", token: String = TOKEN, agreed: Boolean = true, key: AcmeAccountKey? = null) =
        IssueRequest(sub, token, "169.254.77.1", agreed, key)

    @Test fun happyPath() {
        val s = FakeServer(); val steps = ArrayList<String>()
        val res = issuer(s, steps).issue(req("https://Test.duckdns.org/"))
        res as IssueResult.Success
        assertEquals("test.duckdns.org", res.fqdn)
        assertTrue(s.txtCleared)
        assertTrue(steps.first().startsWith("Setting test.duckdns.org to 169.254.77.1"))
        assertTrue(steps.last().startsWith("Certificate received, valid until 2027-01-17"))
        assertTrue(steps.none { it.contains(TOKEN) })
        assertTrue(s.log.any { it.startsWith("POST https://acme.test/chal") })
        val ks = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(res.pkcs12), res.password.toCharArray()) }
        assertEquals(24, res.password.length)
        assertTrue(ks.isKeyEntry(ks.aliases().toList().first()))
        assertNotNull(ks.getKey(ks.aliases().toList().first(), res.password.toCharArray()))
        assertTrue(res.notAfterMs > res.notBeforeMs)
        // the account key can be reused
        val again = issuer(FakeServer()).issue(req(key = res.accountKey))
        assertTrue(again is IssueResult.Success)
    }

    @Test fun badNonceRetriesOnce() {
        val s = FakeServer().apply { badNonceOnce = true }
        assertTrue(issuer(s).issue(req()) is IssueResult.Success)
    }

    @Test fun duckDnsKo() {
        val s = FakeServer().apply { duckAnswer = "KO" }
        val res = issuer(s).issue(req())
        assertTrue(res is IssueResult.DuckDnsRejected)
        assertFalse((res as IssueResult.Failure).message.contains(TOKEN))
        assertTrue(s.log.none { it.contains("acme.test") })
    }

    @Test fun challengeRejected() {
        val s = FakeServer().apply { rejectChallenge = true }
        val res = issuer(s).issue(req())
        assertTrue(res is IssueResult.Rejected)
        assertTrue((res as IssueResult.Failure).message.contains("no TXT found"))
        assertTrue(s.txtCleared)
    }

    @Test fun networkError() {
        val res = issuer(object : HttpTransport {
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
                throw java.io.IOException("boom $TOKEN")
        }).issue(req())
        assertTrue(res is IssueResult.NetworkError)
        assertFalse((res as IssueResult.Failure).message.contains(TOKEN))
    }

    @Test fun rateLimited() {
        val t = object : HttpTransport {
            val s = FakeServer()
            override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
                if (method == "POST" && url.endsWith("/acct"))
                    HttpResponse(429, mapOf("retry-after" to "3600", "replay-nonce" to "z"), """{"type":"urn:ietf:params:acme:error:rateLimited","detail":"too many"}""".toByteArray())
                else s.request(method, url, headers, body)
        }
        val res = issuer(t).issue(req())
        assertTrue(res is IssueResult.RateLimited)
        assertEquals("3600", (res as IssueResult.RateLimited).retryAfter)
    }

    @Test fun refusesWithoutTerms() {
        val s = FakeServer()
        assertTrue(issuer(s).issue(req(agreed = false)) is IssueResult.TermsNotAccepted)
        assertTrue(s.log.isEmpty())
    }

    @Test fun cancelled() {
        val res = CertIssuer(FakeServer(), "https://acme.test/directory", isCancelled = { true }).issue(req())
        assertTrue(res is IssueResult.Cancelled)
    }

    @Test fun inputNormalisation() {
        assertEquals("myname", CertInput.normalizeSubdomain("myname"))
        assertEquals("myname", CertInput.normalizeSubdomain(" MyName.duckdns.org "))
        assertEquals("myname", CertInput.normalizeSubdomain("https://myname.duckdns.org/"))
        assertNull(CertInput.normalizeSubdomain("my name"))
        assertNull(CertInput.normalizeSubdomain(""))
        assertNull(CertInput.normalizeSubdomain("a".repeat(64)))
        assertTrue(CertInput.isValidToken(TOKEN))
        assertFalse(CertInput.isValidToken("nope"))
        assertTrue(issuer(FakeServer()).issue(req(token = "nope")) is IssueResult.InvalidInput)
    }

    @Test fun shouldRenewThirds() {
        val day = 86_400_000L
        for (life in listOf(90L, 64L, 45L)) {
            val nb = 0L; val na = life * day
            assertFalse(shouldRenew(na, nb, na - (life * day / 3) - day))
            assertTrue(shouldRenew(na, nb, na - (life * day / 3) + day))
            assertTrue(shouldRenew(na, nb, na + day))
        }
    }
}
