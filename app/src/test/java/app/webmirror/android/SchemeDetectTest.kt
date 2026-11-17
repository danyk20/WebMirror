package app.webmirror.android

import app.webmirror.android.server.SchemeDetect
import app.webmirror.android.server.SchemeDetect.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemeDetectTest {

    @Test
    fun classifiesFirstByte() {
        assertEquals(Kind.TLS_HANDSHAKE, SchemeDetect.classify(0x16))
        assertEquals(Kind.PLAIN_HTTP, SchemeDetect.classify('G'.code))
        assertEquals(Kind.PLAIN_HTTP, SchemeDetect.classify('P'.code))
        assertEquals(Kind.OTHER, SchemeDetect.classify(0x00))
        assertEquals(Kind.OTHER, SchemeDetect.classify('g'.code))
        assertEquals(Kind.OTHER, SchemeDetect.classify(0x17))
    }

    @Test
    fun redirectKeepsHostPortAndPath() {
        val head = "GET /index.html?x=1 HTTP/1.1\r\nUser-Agent: t\r\nHost: mirror.example.eu:8443\r\n\r\n"
        val out = String(SchemeDetect.redirectToHttps(head)!!, Charsets.US_ASCII)
        assertTrue(out.startsWith("HTTP/1.1 301 "))
        assertTrue(out.contains("Location: https://mirror.example.eu:8443/index.html?x=1\r\n"))
        assertTrue(out.contains("Content-Length: 0"))
    }

    @Test
    fun redirectRefusesMissingOrBadHost() {
        assertNull(SchemeDetect.redirectToHttps("GET / HTTP/1.1\r\n\r\n"))
        assertNull(SchemeDetect.redirectToHttps("GET / HTTP/1.1\r\nHost: a b\r\n\r\n"))
        assertNull(SchemeDetect.redirectToHttps("GET / HTTP/1.1\r\nHost: evil.com/x\r\n\r\n"))
    }

    @Test
    fun redirectFallsBackToRootForOddPath() {
        val out = String(SchemeDetect.redirectToHttps("GET http://x/y HTTP/1.1\r\nhost: h.eu:8443\r\n\r\n")!!)
        assertTrue(out.contains("Location: https://h.eu:8443/\r\n"))
    }

    @Test
    fun headCompleteNeedsEmptyLine() {
        assertFalse(SchemeDetect.headComplete("GET / HTTP/1.1\r\nHost: a\r\n"))
        assertTrue(SchemeDetect.headComplete("GET / HTTP/1.1\r\nHost: a\r\n\r\n"))
    }

    @Test
    fun pageRedirectOnlyForTheCertificateName() {
        assertEquals("https://m.eu:8443/", SchemeDetect.httpsPageRedirect("/", "m.eu:8080", "m.eu", 8443))
        assertEquals("https://m.eu:8443/", SchemeDetect.httpsPageRedirect("/index.html", "M.EU", "m.eu", 8443))
        assertNull(SchemeDetect.httpsPageRedirect("/", "192.168.1.5:8080", "m.eu", 8443))
        assertNull(SchemeDetect.httpsPageRedirect("/player.js", "m.eu:8080", "m.eu", 8443))
        assertNull(SchemeDetect.httpsPageRedirect("/", "m.eu:8080", null, 8443))
        assertNull(SchemeDetect.httpsPageRedirect("/", null, "m.eu", 8443))
    }
}
