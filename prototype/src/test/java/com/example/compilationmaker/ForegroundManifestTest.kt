package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ForegroundManifestTest {
    @Test
    fun sourceManifestDeclaresRequiredForegroundServiceConfiguration() {
        val manifest = locateManifest()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)

        val permissions = document.getElementsByTagName("uses-permission")
        val permissionNames = (0 until permissions.length).map { index ->
            permissions.item(index).attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue
        }.toSet()
        assertTrue(permissionNames.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(permissionNames.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissionNames.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING"))
        assertTrue(permissionNames.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))

        val services = document.getElementsByTagName("service")
        val foregroundServices = (0 until services.length).map { services.item(it) }.filter { service ->
            service.attributes.getNamedItemNS(ANDROID_NS, "name")?.nodeValue == SYSTEM_FOREGROUND_SERVICE
        }
        assertEquals(1, foregroundServices.size)
        val service = foregroundServices.single()
        assertEquals(
            "mediaProcessing|dataSync",
            service.attributes.getNamedItemNS(ANDROID_NS, "foregroundServiceType")?.nodeValue
        )
        assertEquals("merge", service.attributes.getNamedItemNS(TOOLS_NS, "node")?.nodeValue)
    }

    private fun locateManifest(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        return candidates.firstOrNull(File::isFile).also { assertNotNull("Source manifest not found", it) }!!
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"
        const val SYSTEM_FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
    }
}
