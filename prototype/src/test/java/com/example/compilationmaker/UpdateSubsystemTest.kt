package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSubsystemTest {
    @Test
    fun parserRequiresVersionCodeDigestAndTrustedHttpsApk() {
        val valid = updateJson(
            version = "0.4.0",
            versionCode = 5,
            apkUrl = "https://github.com/hughbechainez-byte/NumberChangeDetector/releases/download/v0.4.0/app.apk",
            sha256 = "a".repeat(64)
        )
        assertEquals(5L, UpdateManifestParser.parse(valid).single().versionCode)

        assertTrue(UpdateManifestParser.parse(valid.replace("\"sha256\":\"${"a".repeat(64)}\",", "")).isEmpty())
        assertTrue(UpdateManifestParser.parse(valid.replace("\"versionCode\":5,", "")).isEmpty())
        assertTrue(UpdateManifestParser.parse(valid.replace("https://github.com", "http://github.com")).isEmpty())
        assertTrue(UpdateManifestParser.parse(valid.replace("github.com", "github.com.attacker.invalid")).isEmpty())
    }

    @Test
    fun parserSelectsByIntegerVersionCodeInsteadOfDisplayName() {
        val feed = """
            {
              "updates": [
                ${entry("99.0-display", 8, "b".repeat(64))},
                ${entry("1.0-display", 10, "c".repeat(64))},
                ${entry("2.0-installed", 9, "d".repeat(64))}
              ]
            }
        """.trimIndent()
        val parsed = UpdateManifestParser.parse(feed)

        assertEquals(listOf(10L, 9L, 8L), parsed.map(UpdateInfo::versionCode))
        assertEquals(10L, UpdateManifestParser.latestNewerThan(parsed.reversed(), 9L)?.versionCode)
        assertNull(UpdateManifestParser.latestNewerThan(parsed, 10L))
    }

    @Test
    fun urlPolicyAllowsOnlyExpectedHttpsEndpointsAndRedirectHosts() {
        assertTrue(
            UpdateUrlPolicy.isAllowedManifestUrl(
                "https://raw.githubusercontent.com/hughbechainez-byte/NumberChangeDetector/main/prototype-update.json"
            )
        )
        assertTrue(UpdateUrlPolicy.isAllowedDownloadUrl("https://github.com/owner/repo/releases/download/v1/app.apk"))
        assertTrue(UpdateUrlPolicy.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/path/app.apk?token=x"))
        assertTrue(UpdateUrlPolicy.isAllowedDownloadUrl("https://objects.githubusercontent.com/path/app.apk"))
        assertFalse(UpdateUrlPolicy.isAllowedDownloadUrl("https://raw.githubusercontent.com/owner/repo/app.apk"))
        assertFalse(UpdateUrlPolicy.isAllowedDownloadUrl("https://github.com.evil.invalid/app.apk"))
        assertFalse(UpdateUrlPolicy.isAllowedDownloadUrl("https://user@github.com/app.apk"))
        assertFalse(UpdateUrlPolicy.isAllowedDownloadUrl("https://github.com:444/app.apk"))
    }

    @Test
    fun digestComparisonIsExactAndCaseInsensitive() {
        val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())

        assertEquals(digest, UpdateIntegrity.sha256Hex("abc".toByteArray()))
        assertTrue(UpdateIntegrity.matches(digest.uppercase(), actual))
        assertFalse(UpdateIntegrity.matches("0".repeat(64), actual))
        assertFalse(UpdateIntegrity.matches("not-a-digest", actual))
    }

    @Test
    fun schedulingConstantsMatchForegroundAndPlatformPeriodicRequirements() {
        assertEquals(5L * 60L * 1000L, UpdateScheduler.FOREGROUND_CHECK_INTERVAL_MS)
        assertEquals(15L, UpdateScheduler.BACKGROUND_CHECK_INTERVAL_MINUTES)
        assertEquals(5L, UpdateScheduler.BACKGROUND_FLEX_MINUTES)
    }

    @Test
    fun automaticDownloadNeverCompetesWithAnActiveCompilation() {
        assertTrue(shouldAutomaticallyDownloadUpdate(autoDownloadEnabled = true, activeCompilation = false))
        assertFalse(shouldAutomaticallyDownloadUpdate(autoDownloadEnabled = true, activeCompilation = true))
        assertFalse(shouldAutomaticallyDownloadUpdate(autoDownloadEnabled = false, activeCompilation = false))
        assertFalse(shouldAutomaticallyDownloadUpdate(autoDownloadEnabled = false, activeCompilation = true))
    }

    private fun updateJson(
        version: String,
        versionCode: Int,
        apkUrl: String,
        sha256: String
    ): String = """
        {
          "version":"$version",
          "versionCode":$versionCode,
          "apkUrl":"$apkUrl",
          "releaseUrl":"https://github.com/owner/repo/releases/tag/v$version",
          "sha256":"$sha256",
          "notes":"test"
        }
    """.trimIndent()

    private fun entry(version: String, versionCode: Int, sha256: String): String = """
        {
          "version":"$version",
          "versionCode":$versionCode,
          "apkUrl":"https://github.com/owner/repo/releases/download/v$version/app.apk",
          "sha256":"$sha256"
        }
    """.trimIndent()
}
