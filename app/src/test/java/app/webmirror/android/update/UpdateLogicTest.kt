package app.webmirror.android.update

import org.junit.Assert.*
import org.junit.Test

class UpdateLogicTest {
    @Test fun versionOrdering() {
        assertTrue(Version.isNewer("1.10.0", "1.9.2"))
        assertTrue(Version.isNewer("1.0.0", "1.0.0-beta.1"))
        assertFalse(Version.isNewer("1.0.0-beta.1", "1.0.0"))
        assertEquals(0, Version.compare("v1.2.0", "1.2"))
        assertTrue(Version.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Version.compare("1.0.0-beta.2", "1.0.0-beta.1") > 0)
        assertTrue(Version.compare("1.0.0-beta.11", "1.0.0-beta.2") > 0)
        assertTrue(Version.compare("1.0.0-rc.1", "1.0.0-beta.9") > 0)
        assertEquals(0, Version.compare("1.0.0+build5", "1.0.0"))
    }

    private val json = """{"tag_name":"v1.2.3","name":"Big one","body":"- fixes\n- more","html_url":"https://github.com/o/r/releases/tag/v1.2.3",
        "draft":false,"prerelease":false,"assets":[
        {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":5},
        {"name":"WebMirror-1.2.3.apk","browser_download_url":"https://x/a.apk","size":1234567},
        {"name":"WebMirror-1.2.3.apk.sha256","browser_download_url":"https://x/a.sha","size":90}]}"""

    @Test fun parsesRelease() {
        val r = ReleaseParser.parse(json)!!
        assertEquals("1.2.3", r.version)
        assertEquals("Big one", r.name)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals(1234567L, r.apkSize)
        assertEquals("https://x/a.sha", r.sha256Url)
        assertTrue(r.notes.startsWith("- fixes"))
    }

    @Test fun skipsPrereleaseDraftAndNoApk() {
        assertNull(ReleaseParser.parse(json.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(ReleaseParser.parse(json.replace("\"draft\":false", "\"draft\":true")))
        assertNull(ReleaseParser.parse("""{"tag_name":"v1","assets":[]}"""))
    }

    @Test fun sha256Parsing() {
        val h = "a".repeat(64)
        assertEquals(h, ReleaseParser.parseSha256("${h.uppercase()}  file.apk\n"))
        assertNull(ReleaseParser.parseSha256("nothex"))
    }

    @Test fun autoInstallDecision() {
        assertEquals(AutoAction.NotifyOnly, UpdatePolicy.decideAuto(false, false, true))
        assertEquals(AutoAction.NotifyOnly, UpdatePolicy.decideAuto(true, false, false))
        assertEquals(AutoAction.Postpone, UpdatePolicy.decideAuto(true, true, true))
        assertEquals(AutoAction.Install, UpdatePolicy.decideAuto(true, false, true))
    }

    @Test fun checkDue() {
        assertTrue(UpdatePolicy.isCheckDue(0, 1000))
        assertFalse(UpdatePolicy.isCheckDue(1000, 2000))
        assertTrue(UpdatePolicy.isCheckDue(1000, 1000 + UpdatePolicy.CHECK_INTERVAL_MS))
        assertTrue(UpdatePolicy.isCheckDue(5000, 1000))
    }
}
