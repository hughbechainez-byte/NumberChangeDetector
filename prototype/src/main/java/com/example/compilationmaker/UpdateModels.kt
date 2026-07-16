package com.example.compilationmaker

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

internal data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val releaseUrl: String,
    val downloadUrl: String,
    val sha256: String,
    val releaseNotes: String
)

internal sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val message: String, val retryable: Boolean) : UpdateCheckResult
}

internal sealed interface UpdateDownloadResult {
    data class Ready(val filePath: String) : UpdateDownloadResult
    data class Failed(val message: String, val retryable: Boolean) : UpdateDownloadResult
}

internal object UpdateManifestParser {
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun parse(feedText: String): List<UpdateInfo> {
        val root = runCatching { JSONObject(feedText) }.getOrNull() ?: return emptyList()
        val entries = root.optJSONArray("updates")
        val parsed = if (entries != null) {
            (0 until entries.length()).mapNotNull { index ->
                parseEntry(entries.optJSONObject(index))
            }
        } else {
            listOfNotNull(parseEntry(root))
        }
        return parsed
            .distinctBy(UpdateInfo::versionCode)
            .sortedByDescending(UpdateInfo::versionCode)
    }

    fun latestNewerThan(updates: List<UpdateInfo>, currentVersionCode: Long): UpdateInfo? =
        updates.filter { it.versionCode > currentVersionCode }.maxByOrNull(UpdateInfo::versionCode)

    fun parseEntry(entry: JSONObject?): UpdateInfo? {
        entry ?: return null
        val versionCode = entry.optLong("versionCode", -1L)
        val versionName = entry.optString("version", "").trim().trimStart('v', 'V')
        val downloadUrl = entry.optString("apkUrl", "").trim()
        val sha256 = entry.optString("sha256", "").trim().lowercase(Locale.US)
        if (
            versionCode <= 0L ||
            versionName.isBlank() ||
            !sha256Pattern.matches(sha256) ||
            !UpdateUrlPolicy.isAllowedDownloadUrl(downloadUrl)
        ) {
            return null
        }
        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            releaseUrl = entry.optString("releaseUrl", "").trim(),
            downloadUrl = downloadUrl,
            sha256 = sha256,
            releaseNotes = entry.optString("notes", "").trim()
        )
    }

    fun toJson(info: UpdateInfo): String = JSONObject()
        .put("version", info.versionName)
        .put("versionCode", info.versionCode)
        .put("releaseUrl", info.releaseUrl)
        .put("apkUrl", info.downloadUrl)
        .put("sha256", info.sha256)
        .put("notes", info.releaseNotes)
        .toString()

    fun fromJson(value: String?): UpdateInfo? = value
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { parseEntry(JSONObject(it)) }.getOrNull() }
}

internal object UpdateUrlPolicy {
    private val manifestHosts = setOf("raw.githubusercontent.com")
    private val downloadHosts = setOf(
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )

    fun isAllowedManifestUrl(url: String): Boolean = isAllowedHttpsUrl(url, manifestHosts)

    fun isAllowedDownloadUrl(url: String): Boolean = isAllowedHttpsUrl(url, downloadHosts)

    private fun isAllowedHttpsUrl(url: String, allowedHosts: Set<String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.fragment == null &&
            (uri.port == -1 || uri.port == 443) &&
            host in allowedHosts
    }
}

internal object UpdateIntegrity {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

    fun matches(expectedHex: String, actualDigest: ByteArray): Boolean {
        val expectedBytes = hexToBytes(expectedHex) ?: return false
        return MessageDigest.isEqual(expectedBytes, actualDigest)
    }

    private fun hexToBytes(value: String): ByteArray? {
        if (!Regex("^[0-9a-fA-F]{64}$").matches(value)) return null
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

internal object UpdateContract {
    const val EXTRA_ACTION = "compilationmaker.update.ACTION"
    const val EXTRA_VERSION_CODE = "compilationmaker.update.VERSION_CODE"
    const val ACTION_DOWNLOAD = "download"
    const val ACTION_INSTALL = "install"
}

internal fun shouldAutomaticallyDownloadUpdate(
    autoDownloadEnabled: Boolean,
    activeCompilation: Boolean
): Boolean = autoDownloadEnabled && !activeCompilation
