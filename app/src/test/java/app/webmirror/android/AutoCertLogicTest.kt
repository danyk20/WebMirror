package app.webmirror.android

import app.webmirror.android.net.AutoCertLogic
import app.webmirror.android.net.AutoCertState
import app.webmirror.android.net.acme.IssueResult
import org.junit.Assert.*
import org.junit.Test

class AutoCertLogicTest {
    private val day = 24L * 3600 * 1000
    private val on = AutoCertState(enabled = true, subdomain = "x", token = "t", notBeforeMs = 0, notAfterMs = 90 * day)

    @Test fun disabledOrMissingSecretsNeverRun() {
        assertFalse(AutoCertLogic.shouldRun(AutoCertState(), false, 0, true))
        assertFalse(AutoCertLogic.shouldRun(on.copy(token = ""), false, 0, true))
    }

    @Test fun renewsOnlyInLastThird() {
        assertFalse(AutoCertLogic.shouldRun(on, true, 10 * day, false))
        assertTrue(AutoCertLogic.shouldRun(on, true, 70 * day, false))
    }

    @Test fun noCertificateRuns() {
        assertTrue(AutoCertLogic.shouldRun(on, false, 1 * day, false))
    }

    @Test fun forcedRunsEvenWhenFresh() {
        assertTrue(AutoCertLogic.shouldRun(on, true, 1 * day, true))
    }

    @Test fun backoffAfterFailure() {
        val failed = AutoCertLogic.onFailure(on, 70 * day, "boom")
        assertFalse(AutoCertLogic.shouldRun(failed, true, 70 * day + 1000, false))
        assertTrue(AutoCertLogic.shouldRun(failed, true, 70 * day + AutoCertLogic.RETRY_GAP_MS, false))
        assertTrue(AutoCertLogic.shouldRun(failed, true, 70 * day + 1000, true))
    }

    @Test fun successClearsError() {
        val failed = AutoCertLogic.onFailure(on, 1, "boom")
        val ok = AutoCertLogic.onSuccess(failed, 5, 95, 6, byteArrayOf(1), byteArrayOf(2))
        assertNull(ok.lastError)
        assertEquals(95L, ok.notAfterMs)
        assertTrue(ok.enabled)
    }

    @Test fun notifiesOnlyWhenCloseToExpiry() {
        assertFalse(AutoCertLogic.shouldNotify(on, true, 50 * day))
        assertTrue(AutoCertLogic.shouldNotify(on, true, 80 * day))
        assertTrue(AutoCertLogic.shouldNotify(on, false, 1 * day))
    }

    @Test fun hints() {
        assertTrue(AutoCertLogic.hint(IssueResult.DuckDnsRejected("x")).contains("token"))
        assertTrue(AutoCertLogic.hint(IssueResult.RateLimited(null, "x")).contains("Wait"))
        assertTrue(AutoCertLogic.hint(IssueResult.NetworkError("x")).contains("mobile data"))
    }
}

class DuckDnsUpdateTest {
    private val tok = "123e4567-e89b-12d3-a456-426614174000"
    private fun transport(status: Int, body: String, urls: MutableList<String>) = object : app.webmirror.android.net.acme.HttpTransport {
        override fun request(method: String, url: String, headers: Map<String, String>, body2: ByteArray?): app.webmirror.android.net.acme.HttpResponse {
            urls.add(url); return app.webmirror.android.net.acme.HttpResponse(status, emptyMap(), body.toByteArray())
        }
    }

    @Test fun okAndKo() {
        val urls = ArrayList<String>()
        assertNull(app.webmirror.android.net.acme.DuckDns.updateIp("My.duckdns.org", tok, "169.254.77.2", transport(200, "OK", urls)))
        assertEquals("https://www.duckdns.org/update?domains=my&token=$tok&ip=169.254.77.2", urls.single())
        assertNotNull(app.webmirror.android.net.acme.DuckDns.updateIp("my", tok, "1.2.3.4", transport(200, "KO", urls)))
        assertNotNull(app.webmirror.android.net.acme.DuckDns.updateIp("my", "bad", "1.2.3.4", transport(200, "OK", urls)))
    }
}
