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
        assertEquals(listOf("http://192.168.1.5:8080", "http://198.51.100.1:8080"), urls.addresses)
        assertEquals(listOf("https://a.example.com:8443"), urls.https)
    }

    @Test
    fun buildUrlsWithoutExtras() {
        val urls = Addresses.buildUrls(listOf("10.0.0.2"), null, 8080, emptyList(), 8443)
        assertEquals(listOf("http://10.0.0.2:8080"), urls.addresses)
        assertEquals(listOf("http://10-0-0-2.sslip.io:8080"), urls.names)
        assertEquals(emptyList<String>(), urls.https)
    }

    @Test
    fun ipv6BracketFormZoneStrippedAndOrderedAfterIpv4() {
        val urls = Addresses.buildUrls(
            localIps = listOf("10.15.23.199"),
            virtualIp = null,
            httpPort = 8080,
            httpsNames = emptyList(),
            httpsPort = 8443,
            localIpv6 = listOf("2001:DB8::1%wlan0", "fd00::5"),
        )
        assertEquals(
            listOf("http://10.15.23.199:8080", "http://[2001:db8::1]:8080", "http://[fd00::5]:8080"),
            urls.addresses,
        )
        assertEquals(
            listOf(
                "http://10-15-23-199.sslip.io:8080",
                "http://2001-db8--1.sslip.io:8080",
                "http://fd00--5.sslip.io:8080",
            ),
            urls.names,
        )
    }

    @Test
    fun sslipNames() {
        assertEquals("10-15-23-199.sslip.io", Addresses.sslipHost("10.15.23.199"))
        assertEquals("2001-db8--1.sslip.io", Addresses.sslipHost("2001:db8::1"))
        assertEquals("2a01-4f8-c17-b8f--2.sslip.io", Addresses.sslipHost("2a01:4f8:c17:b8f::2%rmnet0"))
    }

    @Test
    fun formatsIpv6Compressed() {
        fun b(vararg g: Int) = ByteArray(16) { ((g[it / 2] shr (if (it % 2 == 0) 8 else 0)) and 0xff).toByte() }
        assertEquals("2001:db8::1", Addresses.formatIpv6(b(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1)))
        assertEquals("2001:db8:0:1:1:1:1:1", Addresses.formatIpv6(b(0x2001, 0xdb8, 0, 1, 1, 1, 1, 1)))
        assertEquals("2a01:4f8:c17:b8f::2", Addresses.formatIpv6(b(0x2a01, 0x4f8, 0xc17, 0xb8f, 0, 0, 0, 2)))
        assertEquals("fd00::", Addresses.formatIpv6(b(0xfd00, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals("2001:0:0:1::1", Addresses.formatIpv6(b(0x2001, 0, 0, 1, 0, 0, 0, 1)))
    }
}
