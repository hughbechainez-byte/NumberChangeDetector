package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CheckpointProfilesTest {
    @Test
    fun prototypeProfilesDescribeTheirRealSamplingIntervals() {
        val profiles = compilationScanProfiles()

        assertEquals(
            listOf(
                "Prototype Fast PTS (30s)",
                "Prototype Balanced PTS (10s)",
                "Prototype Precise PTS (3s)",
                "Legacy Experimental (125ms)"
            ),
            profiles.map { it.label }
        )
        assertEquals(listOf(30_000L, 10_000L, 3_000L, 125L), profiles.map { it.frameStepMs })
        assertEquals(
            listOf(
                ScanMode.StableCheckpoint,
                ScanMode.StableCheckpoint,
                ScanMode.StableCheckpoint,
                ScanMode.Experimental
            ),
            profiles.map { it.mode }
        )
    }

    @Test
    fun clipPlanTransitionSummariesShowExactTimestamps() {
        val summaries = transitionSummariesFromClipPlan(
            """{
              "transitionSummaries": [
                {"fromNumber": null, "toNumber": 1, "eventBoundaryMs": 30000, "confidence": 0.61},
                {"fromNumber": 9, "toNumber": 10, "actualFramePtsMs": 3560000, "confidence": 0.92}
              ]
            }""".trimIndent()
        )

        assertEquals(
            listOf(
                "none -> 1  |  00:30.000  |  61%",
                "9 -> 10  |  59:20.000  |  92%"
            ),
            summaries
        )
    }

    @Test
    fun scanIntervalControlsAreVisibleInTheAppLayout() {
        val layout = locateLayout()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(layout)

        listOf("scanIntervalLabel", "scanSpeedPicker", "transitionResultsLabel", "transitionResultsText").forEach { id ->
            val node = (0 until document.getElementsByTagName("*").length)
                .map { document.getElementsByTagName("*").item(it) }
                .first { it.attributes?.getNamedItemNS(ANDROID_NS, "id")?.nodeValue == "@+id/$id" }
            assertFalse(node.attributes?.getNamedItemNS(ANDROID_NS, "visibility")?.nodeValue == "gone")
        }
    }

    private fun locateLayout(): File {
        return listOf(
            File("src/main/res/layout/activity_main.xml"),
            File("app/src/main/res/layout/activity_main.xml")
        ).first(File::isFile)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
