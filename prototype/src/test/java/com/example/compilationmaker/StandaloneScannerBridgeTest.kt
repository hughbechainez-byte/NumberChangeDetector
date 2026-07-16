package com.example.compilationmaker

import com.hughbechainez.numberchangedetector.scanner.DetectionProgress
import com.hughbechainez.numberchangedetector.scanner.ScanMetrics
import com.hughbechainez.numberchangedetector.scanner.ScanProfile
import com.hughbechainez.numberchangedetector.scanner.ScanWindow
import com.hughbechainez.numberchangedetector.scanner.StatePoint
import com.hughbechainez.numberchangedetector.scanner.TransitionDetectionResult
import com.hughbechainez.numberchangedetector.scanner.TransitionMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneScannerBridgeTest {
    @Test
    fun profileMappingKeepsPrototypeFastAndAccurateChoices() {
        assertEquals(ScanProfile.FAST, standaloneProfileFor(500L))
        assertEquals(ScanProfile.PRECISE, standaloneProfileFor(250L))
        assertEquals(ScanProfile.PRECISE, standaloneProfileFor(3_000L))
        assertEquals(ScanProfile.BALANCED, standaloneProfileFor(10_000L))
        assertEquals(ScanProfile.FAST, standaloneProfileFor(30_000L))
        assertEquals(ScanProfile.FAST, standaloneProfileFor(60_000L))
        assertEquals(ScanProfile.FAST, standaloneProfileFor(180_000L))
        assertEquals(ScanProfile.MONOTONIC_3_MIN, standaloneProfileFor(180_000L, "MONOTONIC_3_MIN"))
    }

    @Test
    fun progressMapsIntoCompilationScanRangeWithoutOvertakingExport() {
        val coarse = mapStandaloneProgress(DetectionProgress("coarse_scan", "Checkpoint", 60))
        val refining = mapStandaloneProgress(DetectionProgress("refining", "Refining", 98))
        val completed = mapStandaloneProgress(DetectionProgress("completed", "Done", 100))

        assertEquals(CompilationPipelineState.COARSE_SCAN, coarse.state)
        assertEquals(32, coarse.percent)
        assertEquals(CompilationPipelineState.REFINING, refining.state)
        assertEquals(52, refining.percent)
        assertEquals(CompilationPipelineState.BUILDING_CLIP_PLAN, completed.state)
        assertEquals(54, completed.percent)
    }

    @Test
    fun resultMappingUsesActualPtsAndProducesExactClipPlan() {
        val result = fixtureResult()
        val mapped = mapStandaloneResult(result)

        assertEquals(listOf(30_000L, 75_000L), mapped.candidateTimestampsMs)
        assertEquals(2, mapped.candidateCount)
        assertEquals(0, mapped.rejectedTransitionCount)
        assertEquals(121, mapped.completedCheckpointCount)
        assertEquals(false, mapped.strategyFallbackUsed)
        assertNull(mapped.strategyFallbackReason)
        assertEquals(
            listOf(SegmentWindow(20_000L, 60_000L), SegmentWindow(65_000L, 105_000L)),
            mapped.segments
        )
        assertNull(mapped.transitionSummaries.first().fromNumber)
        assertEquals(1, mapped.transitionSummaries.first().toNumber)
    }

    @Test
    fun compatibleReportAndClipPlanExposeExactTransitionSummaries() {
        val result = fixtureResult()
        val mapped = mapStandaloneResult(result)
        val report = standaloneReportJson(result, mapped, savedAtMs = 123L)

        assertEquals("v1-sparse-pts-ocr", report.getString("scannerVersion"))
        assertEquals("Prototype Fast PTS (30s)", report.getString("profileLabel"))
        assertEquals(2, report.getJSONArray("transitionMarks").length())
        val firstMark = report.getJSONArray("transitionMarks").getJSONObject(0)
        assertEquals(30_000L, firstMark.getLong("eventBoundaryMs"))
        assertEquals(30_000L, firstMark.getLong("actualFramePtsMs"))
        assertTrue(firstMark.isNull("fromNumber"))
        assertEquals(1, firstMark.getInt("toNumber"))

        val summaries = report.getJSONObject("clipPlan").getJSONArray("transitionSummaries")
        assertEquals(2, summaries.length())
        assertEquals(75_000L, summaries.getJSONObject(1).getLong("actualFramePtsMs"))
    }

    @Test
    fun monotonicFallbackIsPersistedInBridgeResultAndReport() {
        val reason = "Monotonic turbo fell back to the proven 30-second scan: skipped state"
        val result = fixtureResult(
            warnings = listOf(reason),
            profile = ScanProfile.MONOTONIC_3_MIN
        )
        val mapped = mapStandaloneResult(result)
        val report = standaloneReportJson(result, mapped, savedAtMs = 123L)

        assertTrue(mapped.strategyFallbackUsed)
        assertEquals(reason, mapped.strategyFallbackReason)
        assertTrue(report.getBoolean("fallbackUsed"))
        assertEquals(reason, report.getString("failureReason"))
        assertEquals("Prototype Fast PTS (30s)", report.getString("effectiveProfileLabel"))
        assertEquals(180_000L, report.getLong("requestedCheckpointIntervalMs"))
        assertEquals(30_000L, report.getLong("effectiveCheckpointIntervalMs"))
    }

    private fun fixtureResult(
        warnings: List<String> = emptyList(),
        profile: ScanProfile = ScanProfile.FAST
    ): TransitionDetectionResult = TransitionDetectionResult(
        sourceUri = "content://fixture/video-a",
        videoDurationMs = 180_000L,
        roi = ScanWindow(0f, 0.8f, 0.1f, 0.2f),
        profile = profile,
        transitions = listOf(
            TransitionMark(
                eventBoundaryMs = 30_000L,
                actualFramePtsMs = 30_000L,
                fromNumber = null,
                toNumber = 1,
                confidence = 0.61f,
                evidence = emptyList()
            ),
            TransitionMark(
                eventBoundaryMs = 75_000L,
                actualFramePtsMs = 75_000L,
                fromNumber = 1,
                toNumber = 2,
                confidence = 0.82f,
                evidence = emptyList()
            )
        ),
        checkpoints = listOf(StatePoint(0L, null), StatePoint(30_000L, 1)),
        metrics = ScanMetrics(
            scannerVersion = "v1-sparse-pts-ocr",
            wallClockMs = 1_000L,
            checkpointCount = 121,
            decodedFrameCount = 20,
            ocrInferenceCount = 30,
            candidateCount = 2,
            confirmedTransitionCount = 2,
            videoToWallSpeed = 180f
        ),
        warnings = warnings
    )
}
