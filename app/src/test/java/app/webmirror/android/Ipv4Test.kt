package app.webmirror.android

import app.webmirror.android.net.Addresses
import app.webmirror.android.net.CertNames
import app.webmirror.android.net.Ipv4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4Test {
    @Test
    fun acceptsValid() {
        for (ip in listOf("198.51.100.1", "3.3.3.3", "0.0.0.0", "255.255.255.255", "10.0.0.254")) {
            assertTrue(ip, Ipv4.isValid(ip))
        }
    }

    @Test
    fun rejectsInvalid() {
        for (ip in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.256", "a.b.c.d", "1..2.3", "1.2.3.", " 1.2.3.4", "1.2.3.4 ", "-1.2.3.4", "01.2.3.4", "1.2.3.0004", "1,2,3,4", "+1.2.3.4")) {
            assertFalse("'$ip'", Ipv4.isValid(ip))
        }
    }
}

class CertNamesTest {
    @Test
    fun commonNameFromRfc2253() {
        assertEquals("example.com", CertNames.commonName("CN=example.com"))
        assertEquals("example.com", CertNames.commonName("O=Acme\\, Inc,CN=example.com,C=US"))
        assertEquals("a,b", CertNames.commonName("CN=a\\,b,O=x"))
        assertNull(CertNames.commonName("O=Acme"))
    }

    @Test
    fun hostNamesSkipWildcardsAndFallBackToCn() {
        assertEquals(listOf("a.example.com"), CertNames.hostNames(listOf("*.example.com", "a.example.com", "a.example.com"), "x"))
        assertEquals(listOf("my.duckdns.org"), CertNames.hostNames(emptyList(), "my.duckdns.org"))
        assertEquals(emptyList<String>(), CertNames.hostNames(listOf("*.example.com"), "*.example.com"))
        assertEquals(emptyList<String>(), CertNames.hostNames(emptyList(), "localhost"))
    }
}

class AddressesTest {
    @Test
    fun buildUrlsListsVirtualAndHttpsWithoutDuplicates() {
        val urls = Addresses.buildUrls(
            localIps = listOf("192.168.1.5", "198.51.100.1"),
            virtualIp = "198.51.100.1",
            httpPort = 8080,
            httpsNames = listOf("a.example.com", "a.example.com"),
            httpsPort = 8443,
        )
        assertEquals(
            listOf("http://192.168.1.5:8080", "http://198.51.100.1:8080", "https://a.example.com:8443"),
            urls,
        )
    }

    @Test
    fun buildUrlsWithoutExtras() {
        assertEquals(listOf("http://10.0.0.2:8080"), Addresses.buildUrls(listOf("10.0.0.2"), null, 8080, emptyList(), 8443))
    }
}
