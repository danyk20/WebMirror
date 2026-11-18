package app.webmirror.android.net.acme

import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/** Minimal DER encoder, just enough for a PKCS#10 certification request. */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(length(content.size))
        out.write(content)
        return out.toByteArray()
    }

    fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray) = tlv(0x30, concat(*parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(*parts))
    fun integer(v: Int): ByteArray = tlv(0x02, byteArrayOf(v.toByte()))
    fun octetString(b: ByteArray) = tlv(0x04, b)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun bitString(b: ByteArray) = tlv(0x03, concat(byteArrayOf(0), b))

    /** OID from dotted notation. */
    fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (x in p.drop(2)) {
            val chunks = ArrayList<Int>()
            var v = x
            chunks.add((v and 0x7f).toInt())
            v = v shr 7
            while (v > 0) { chunks.add(((v and 0x7f) or 0x80).toInt()); v = v shr 7 }
            chunks.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** PKCS#10 CSR, CN=[fqdn], SAN dNSName=[fqdn], signed with ecdsa-with-SHA256. */
    fun csr(fqdn: String, publicKey: PublicKey, privateKey: PrivateKey): ByteArray {
        val name = sequence(set(sequence(oid("2.5.4.3"), utf8(fqdn))))
        val san = sequence(tlv(0x82, fqdn.toByteArray(Charsets.US_ASCII)))
        val extensions = sequence(sequence(oid("2.5.29.17"), octetString(san)))
        val attribute = sequence(oid("1.2.840.113549.1.9.14"), set(extensions))
        val info = sequence(integer(0), name, publicKey.encoded, tlv(0xA0, attribute))
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey); update(info); sign()
        }
        return sequence(info, sequence(oid("1.2.840.10045.4.3.2")), bitString(sig))
    }
}
