package app.webmirror.android.update

import app.webmirror.android.net.acme.MiniJson

data class ReleaseInfo(
    /** Version without leading "v". */
    val version: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** URL of the `<apk>.sha256` asset, if the release has one. */
    val sha256Url: String?,
)

object ReleaseParser {
    /**
     * Parses a GitHub "latest release" JSON. Returns null for drafts, prereleases, or releases without an .apk asset.
     * Throws IllegalArgumentException for malformed JSON.
     */
    fun parse(json: String): ReleaseInfo? {
        val o = MiniJson.parseObject(json)
        if (o["draft"] == true || o["prerelease"] == true) return null
        val tag = (o["tag_name"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val assets = (o["assets"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val apk = assets.firstOrNull { (it["name"] as? String)?.endsWith(".apk", ignoreCase = true) == true } ?: return null
        val apkName = apk["name"] as String
        val apkUrl = apk["browser_download_url"] as? String ?: return null
        val shaName = "$apkName.sha256"
        val sha = assets.firstOrNull { it["name"] == shaName }?.get("browser_download_url") as? String
        val version = Version.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = (o["name"] as? String)?.takeIf { it.isNotBlank() } ?: "WebMirror $version",
            notes = (o["body"] as? String).orEmpty().trim(),
            htmlUrl = o["html_url"] as? String ?: "",
            apkName = apkName,
            apkUrl = apkUrl,
            apkSize = (apk["size"] as? Number)?.toLong() ?: -1L,
            sha256Url = sha,
        )
    }

    /** First 64-hex token of a `.sha256` file ("<hex>  filename" or just "<hex>"), lower-case, or null. */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}
