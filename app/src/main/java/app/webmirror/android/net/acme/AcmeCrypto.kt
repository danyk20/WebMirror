package app.webmirror.android.net.acme

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The ACME account key in storable form (PKCS#8 private + X.509 public). */
class AcmeAccountKey(val pkcs8: ByteArray, val x509: ByteArray) {
    internal fun toKeyPair(): KeyPair {
        val kf = KeyFactory.getInstance("EC")
        return KeyPair(kf.generatePublic(X509EncodedKeySpec(x509)), kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8)))
    }

    companion object {
        internal fun from(kp: KeyPair) = AcmeAccountKey(kp.private.encoded, kp.public.encoded)
    }
}

internal object AcmeCrypto {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun b64url(bytes: ByteArray): String = b64.encodeToString(bytes)
    fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun generateEcKey(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun unsigned32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** JWK members in lexicographic order, ready for both the header and the RFC 7638 thumbprint. */
    fun jwk(pub: ECPublicKey): Map<String, Any?> = linkedMapOf(
        "crv" to "P-256",
        "kty" to "EC",
        "x" to b64url(unsigned32(pub.w.affineX)),
        "y" to b64url(unsigned32(pub.w.affineY)),
    )

    fun thumbprint(pub: ECPublicKey): String = b64url(sha256(MiniJson.write(jwk(pub)).toByteArray(Charsets.UTF_8)))

    /** Converts a DER ECDSA signature into the fixed 64-byte R||S of JWS ES256. */
    fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += (der[1].toInt() and 0x7f)
        require(der[i].toInt() == 0x02) { "bad DER signature" }
        val rl = der[i + 1].toInt() and 0xff
        val r = BigInteger(1, der.copyOfRange(i + 2, i + 2 + rl))
        i += 2 + rl
        require(der[i].toInt() == 0x02) { "bad DER signature" }
        val sl = der[i + 1].toInt() and 0xff
        val s = BigInteger(1, der.copyOfRange(i + 2, i + 2 + sl))
        return unsigned32(r) + unsigned32(s)
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun int(b: ByteArray) = Der.tlv(0x02, BigInteger(1, b).toByteArray())
        return Der.sequence(int(raw.copyOfRange(0, 32)), int(raw.copyOfRange(32, 64)))
    }

    /** Builds a flattened-free JWS (the JSON body to POST). [kid] null means embed the jwk. */
    fun jws(key: KeyPair, url: String, nonce: String, kid: String?, payload: String): String {
        val header = linkedMapOf<String, Any?>("alg" to "ES256", "nonce" to nonce, "url" to url)
        if (kid != null) header["kid"] = kid else header["jwk"] = jwk(key.public as ECPublicKey)
        val protectedB64 = b64url(MiniJson.write(header).toByteArray(Charsets.UTF_8))
        val payloadB64 = b64url(payload.toByteArray(Charsets.UTF_8))
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update("$protectedB64.$payloadB64".toByteArray(Charsets.US_ASCII)); sign()
        }
        return MiniJson.write(linkedMapOf("protected" to protectedB64, "payload" to payloadB64, "signature" to b64url(derToRaw(der))))
    }
}
