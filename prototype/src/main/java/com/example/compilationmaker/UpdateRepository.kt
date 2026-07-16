package com.example.compilationmaker

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class UpdateRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()

    var autoDownloadEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_DOWNLOAD, false)
        set(value) {
            preferences.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()
        }

    suspend fun checkForUpdate(): UpdateCheckResult = checkMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val feed = fetchManifest()
                val updates = UpdateManifestParser.parse(feed)
                if (updates.isEmpty()) {
                    return@withContext UpdateCheckResult.Failed(
                        "The update feed did not contain a valid signed-version entry.",
                        retryable = false
                    )
                }
                val latest = UpdateManifestParser.latestNewerThan(
                    updates = updates,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong()
                ) ?: return@withContext UpdateCheckResult.UpToDate
                saveAvailableUpdate(latest)
                UpdateCheckResult.Available(latest)
            } catch (failure: IOException) {
                UpdateCheckResult.Failed("Could not reach the update feed.", retryable = true)
            } catch (failure: Exception) {
                AppLog.w(appContext, TAG, "Update check failed", failure)
                UpdateCheckResult.Failed("The update feed could not be verified.", retryable = false)
            }
        }
    }

    suspend fun downloadAndVerify(info: UpdateInfo): UpdateDownloadResult = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val updateDir = File(appContext.filesDir, UPDATE_DIRECTORY)
            if (!updateDir.exists() && !updateDir.mkdirs()) {
                return@withContext UpdateDownloadResult.Failed(
                    "Could not create private update storage.",
                    retryable = false
                )
            }
            val target = downloadedFile(info.versionCode)
            val partial = File(updateDir, "CompilationMaker-${info.versionCode}.apk.part")
            try {
                if (target.isFile && verifyDownloadedFile(target, info) == null) {
                    return@withContext UpdateDownloadResult.Ready(target.absolutePath)
                }
                target.delete()
                partial.delete()
                downloadToPartial(info.downloadUrl, partial, info.sha256)
                if (target.exists() && !target.delete()) {
                    throw IOException("Could not replace an older update")
                }
                if (!partial.renameTo(target)) {
                    throw IOException("Could not finalize the verified update")
                }
                val preflightFailure = verifyDownloadedFile(target, info)
                if (preflightFailure != null) {
                    target.delete()
                    return@withContext UpdateDownloadResult.Failed(preflightFailure, retryable = false)
                }
                removeOtherUpdateFiles(updateDir, target)
                saveAvailableUpdate(info)
                UpdateDownloadResult.Ready(target.absolutePath)
            } catch (failure: UpdateSecurityException) {
                partial.delete()
                target.delete()
                AppLog.w(appContext, TAG, "Downloaded update failed verification", failure)
                UpdateDownloadResult.Failed(failure.message ?: "Update verification failed.", retryable = false)
            } catch (failure: IOException) {
                partial.delete()
                AppLog.w(appContext, TAG, "Update download failed", failure)
                UpdateDownloadResult.Failed("Update download did not complete.", retryable = true)
            } catch (failure: Exception) {
                partial.delete()
                target.delete()
                AppLog.w(appContext, TAG, "Update preparation failed", failure)
                UpdateDownloadResult.Failed("Update preparation failed.", retryable = false)
            }
        }
    }

    suspend fun verifiedDownloadedFile(versionCode: Long): File? = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val info = loadAvailableUpdate()?.takeIf { it.versionCode == versionCode }
                ?: return@withContext null
            val file = downloadedFile(versionCode)
            file.takeIf { it.isFile && verifyDownloadedFile(it, info) == null }
        }
    }

    fun loadAvailableUpdate(): UpdateInfo? =
        UpdateManifestParser.fromJson(preferences.getString(KEY_AVAILABLE_UPDATE, null))

    fun shouldNotify(info: UpdateInfo, readyToInstall: Boolean): Boolean {
        val key = if (readyToInstall) KEY_NOTIFIED_READY else KEY_NOTIFIED_AVAILABLE
        return preferences.getLong(key, -1L) != info.versionCode
    }

    fun markNotified(info: UpdateInfo, readyToInstall: Boolean) {
        val key = if (readyToInstall) KEY_NOTIFIED_READY else KEY_NOTIFIED_AVAILABLE
        preferences.edit().putLong(key, info.versionCode).apply()
    }

    private fun saveAvailableUpdate(info: UpdateInfo) {
        preferences.edit()
            .putString(KEY_AVAILABLE_UPDATE, UpdateManifestParser.toJson(info))
            .apply()
    }

    private fun fetchManifest(): String {
        if (!UpdateUrlPolicy.isAllowedManifestUrl(MANIFEST_URL)) {
            throw UpdateSecurityException("Update feed URL is not trusted.")
        }
        val connection = openFollowingRedirects(
            initialUrl = MANIFEST_URL,
            isAllowed = UpdateUrlPolicy::isAllowedManifestUrl,
            accept = "application/json"
        )
        return try {
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_MANIFEST_BYTES) {
                throw UpdateSecurityException("Update feed is unexpectedly large.")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) {
                        throw UpdateSecurityException("Update feed is unexpectedly large.")
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToPartial(url: String, partial: File, expectedSha256: String) {
        val connection = openFollowingRedirects(
            initialUrl = url,
            isAllowed = UpdateUrlPolicy::isAllowedDownloadUrl,
            accept = "application/vnd.android.package-archive,application/octet-stream"
        )
        try {
            val declaredLength = connection.contentLengthLong
            if (declaredLength == 0L || declaredLength > MAX_APK_BYTES) {
                throw UpdateSecurityException("Update APK size is invalid.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(partial).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) {
                            throw UpdateSecurityException("Update APK exceeded the download limit.")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    if (total <= 0L) {
                        throw UpdateSecurityException("Update APK was empty.")
                    }
                }
                output.fd.sync()
            }
            if (!UpdateIntegrity.matches(expectedSha256, digest.digest())) {
                throw UpdateSecurityException("Update SHA-256 did not match the release feed.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingRedirects(
        initialUrl: String,
        isAllowed: (String) -> Boolean,
        accept: String
    ): HttpURLConnection {
        var current = URI(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val currentUrl = current.toString()
            if (!isAllowed(currentUrl)) {
                throw UpdateSecurityException("Update server redirect was not trusted.")
            }
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "CompilationMaker-Prototype-Updater")
            connection.connect()
            when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank() || redirectCount >= MAX_REDIRECTS) {
                        throw UpdateSecurityException("Update server used an invalid redirect.")
                    }
                    current = current.resolve(location)
                }
                in 200..299 -> return connection
                else -> {
                    connection.disconnect()
                    throw IOException("Update server returned HTTP $responseCode")
                }
            }
        }
        throw UpdateSecurityException("Too many update redirects.")
    }

    private fun verifyDownloadedFile(file: File, info: UpdateInfo): String? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_APK_BYTES) {
            return "Downloaded update file is invalid."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_APK_BYTES) return "Downloaded update file is too large."
                digest.update(buffer, 0, read)
            }
        }
        if (!UpdateIntegrity.matches(info.sha256, digest.digest())) {
            return "Downloaded update SHA-256 is invalid."
        }

        val packageManager = appContext.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "Downloaded file is not a readable APK."
        @Suppress("DEPRECATION")
        val installed = try {
            packageManager.getPackageInfo(appContext.packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return "Installed application identity could not be verified."
        }
        if (archive.packageName != appContext.packageName) {
            return "Downloaded APK belongs to a different application."
        }
        if (packageVersionCode(archive) != info.versionCode) {
            return "Downloaded APK version does not match the release feed."
        }
        if (info.versionCode <= packageVersionCode(installed)) {
            return "Downloaded APK is not newer than the installed application."
        }
        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty() || archiveSigners != installedSigners) {
            return "Downloaded APK signing certificate does not match this application."
        }
        return null
    }

    private fun packageVersionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: emptyArray()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte ->
                    "%02x".format(Locale.US, byte.toInt() and 0xff)
                }
        }
    }

    private fun downloadedFile(versionCode: Long): File =
        File(File(appContext.filesDir, UPDATE_DIRECTORY), "CompilationMaker-$versionCode.apk")

    private fun removeOtherUpdateFiles(directory: File, keep: File) {
        directory.listFiles()?.forEach { candidate ->
            if (candidate.absolutePath != keep.absolutePath) candidate.delete()
        }
    }

    private class UpdateSecurityException(message: String) : Exception(message)

    companion object {
        private const val TAG = "UpdateRepository"
        private const val PREFERENCES_NAME = "update_prefs"
        private const val KEY_AUTO_DOWNLOAD = "auto_download_updates"
        private const val KEY_AVAILABLE_UPDATE = "available_update"
        private const val KEY_NOTIFIED_AVAILABLE = "notified_available_version_code"
        private const val KEY_NOTIFIED_READY = "notified_ready_version_code"
        private const val UPDATE_DIRECTORY = "updates"
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/hughbechainez-byte/NumberChangeDetector/main/prototype-update.json"
        private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L
        private const val MAX_APK_BYTES = 150L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 45_000

        @Volatile
        private var instance: UpdateRepository? = null

        fun get(context: Context): UpdateRepository = instance ?: synchronized(this) {
            instance ?: UpdateRepository(context).also { instance = it }
        }
    }
}
