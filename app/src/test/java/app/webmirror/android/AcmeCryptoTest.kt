package app.webmirror.android

import app.webmirror.android.net.acme.*
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64

class AcmeCryptoTest {
    private val key by lazy { AcmeCrypto.generateEcKey() }

    @Test fun jwsSignatureIsRawAndVerifies() {
        val body = MiniJson.parseObject(AcmeCrypto.jws(key, "https://x/y", "nonce1", null, "{\"a\":1}"))
        val raw = Base64.getUrlDecoder().decode(body["signature"] as String)
        assertEquals(64, raw.size)
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key.public); update("${body["protected"]}.${body["payload"]}".toByteArray()); verify(AcmeCrypto.rawToDer(raw))
        }
        assertTrue(ok)
        val prot = MiniJson.parseObject(String(Base64.getUrlDecoder().decode(body["protected"] as String)))
        assertEquals("ES256", prot["alg"]); assertEquals("nonce1", prot["nonce"]); assertNotNull(prot["jwk"])
        val withKid = MiniJson.parseObject(String(Base64.getUrlDecoder().decode(
            MiniJson.parseObject(AcmeCrypto.jws(key, "u", "n", "kid1", ""))["protected"] as String)))
        assertEquals("kid1", withKid["kid"]); assertNull(withKid["jwk"])
    }

    @Test fun thumbprintMatchesManualComputation() {
        val pub = key.public as ECPublicKey
        val j = AcmeCrypto.jwk(pub)
        val manual = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${j["x"]}\",\"y\":\"${j["y"]}\"}"
        assertEquals(AcmeCrypto.b64url(AcmeCrypto.sha256(manual.toByteArray())), AcmeCrypto.thumbprint(pub))
        assertEquals(43, AcmeCrypto.thumbprint(pub).length)
    }

    @Test fun accountKeyRoundTrips() {
        val stored = AcmeAccountKey(key.private.encoded, key.public.encoded)
        val back = stored.toKeyPair()
        assertArrayEquals(key.public.encoded, back.public.encoded)
        assertArrayEquals(key.private.encoded, back.private.encoded)
    }

    @Test fun derLengths() {
        assertArrayEquals(byteArrayOf(5), Der.length(5))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x80.toByte()), Der.length(128))
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x01, 0x00), Der.length(256))
        assertArrayEquals(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), Der.oid("2.5.4.3"))
        assertArrayEquals(byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x09, 0x0E), Der.oid("1.2.840.113549.1.9.14"))
    }

    @Test fun csrStructureAndSignature() {
        val csr = Der.csr("test.duckdns.org", key.public, key.private)
        assertEquals(0x30, csr[0].toInt() and 0xff)
        // outer length matches
        var i = 1; var len = csr[i++].toInt() and 0xff
        if (len and 0x80 != 0) { val n = len and 0x7f; len = 0; repeat(n) { len = (len shl 8) or (csr[i++].toInt() and 0xff) } }
        assertEquals(csr.size, i + len)
        // info is first element: parse its extent and verify the signature over it
        assertEquals(0x30, csr[i].toInt() and 0xff)
        var j = i + 1; var il = csr[j++].toInt() and 0xff
        if (il and 0x80 != 0) { val n = il and 0x7f; il = 0; repeat(n) { il = (il shl 8) or (csr[j++].toInt() and 0xff) } }
        val info = csr.copyOfRange(i, j + il)
        val rest = csr.copyOfRange(j + il, csr.size)
        assertEquals(0x30, rest[0].toInt()) // AlgorithmIdentifier
        val bitIdx = 2 + (rest[1].toInt() and 0xff)
        assertEquals(0x03, rest[bitIdx].toInt())
        val sigDer = rest.copyOfRange(bitIdx + 3, rest.size)
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(key.public); update(info); verify(sigDer) }
        assertTrue(ok)
        val text = String(info, Charsets.ISO_8859_1)
        assertTrue(text.contains("test.duckdns.org"))
        assertTrue(info.indexOfFirst { it == 0xA0.toByte() } > 0)
    }
}
