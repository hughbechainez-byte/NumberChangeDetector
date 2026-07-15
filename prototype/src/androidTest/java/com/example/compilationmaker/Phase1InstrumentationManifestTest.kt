package com.example.compilationmaker

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class Phase1InstrumentationManifestTest {
    private val clipManifest = listOf(
        "short-clip-15s",
        "short-clip-90s",
        "long-clip-10m",
        "long-clip-60m",
        "edge-case-highres",
        "edge-case-lowlight"
    )

    @Test
    fun clipManifestContainsAllRequiredBuckets() {
        assertEquals(6, clipManifest.size)
        assertTrue(clipManifest.contains("short-clip-15s"))
        assertTrue(clipManifest.contains("short-clip-90s"))
        assertTrue(clipManifest.contains("long-clip-10m"))
        assertTrue(clipManifest.contains("long-clip-60m"))
        assertTrue(clipManifest.contains("edge-case-highres"))
        assertTrue(clipManifest.contains("edge-case-lowlight"))
    }
}
