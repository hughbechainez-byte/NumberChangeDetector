package com.example.compilationmaker

import android.content.Context
import android.net.Uri
import com.hughbechainez.numberchangedetector.scanner.CornerNumberTransitionDetector
import com.hughbechainez.numberchangedetector.scanner.DetectionProgress
import com.hughbechainez.numberchangedetector.scanner.DigitEvidence
import com.hughbechainez.numberchangedetector.scanner.ScanProfile as DetectorScanProfile
import com.hughbechainez.numberchangedetector.scanner.ScanWindow as DetectorScanWindow
import com.hughbechainez.numberchangedetector.scanner.StatePoint
import com.hughbechainez.numberchangedetector.scanner.TransitionDetectionRequest
import com.hughbechainez.numberchangedetector.scanner.TransitionDetectionResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private fun JSONObject.putFinite(key: String, value: Float, fallback: Float = 0f) {
    put(key, if (value.isFinite()) value else fallback)
}

internal data class ScanTransitionSummary(
    val eventBoundaryMs: Long,
    val actualFramePtsMs: Long,
    val fromNumber: Int?,
    val toNumber: Int,
    val confidence: Float,
    val confirmation: String
)

internal data class StandaloneScanBridgeResult(
    val segments: List<SegmentWindow>,
    val videoDurationMs: Long,
    val reportPath: String,
    val candidateCount: Int,
    val candidateTimestampsMs: List<Long>,
    val rejectedTransitionCount: Int,
    val completedCheckpointCount: Int,
    val transitionSummaries: List<ScanTransitionSummary>
)

internal data class StandaloneProgress(
    val state: CompilationPipelineState,
    val message: String,
    val percent: Int
)

/**
 * Adapts the reusable PTS-aware scanner to CompilationMaker's durable worker contract.
 * The scanner owns rotation normalization, so the ROI supplied here is display-upright.
 */
internal class StandaloneScannerBridge(private val context: Context) {
    suspend fun scan(
        sourceUri: Uri,
        scanWindow: ScanWindow,
        requestedIntervalMs: Long,
        progress: (CompilationPipelineState, String, Int) -> Unit
    ): StandaloneScanBridgeResult {
        val result = CornerNumberTransitionDetector(context).detect(
            request = TransitionDetectionRequest(
                sourceUri = sourceUri,
                roi = DetectorScanWindow(
                    xFraction = scanWindow.xPercent,
                    yFraction = scanWindow.yPercent,
                    widthFraction = scanWindow.widthPercent,
                    heightFraction = scanWindow.heightPercent
                ),
                profile = standaloneProfileFor(requestedIntervalMs),
                targetFrameWidthPx = 640
            )
        ) { detectorProgress ->
            val mapped = mapStandaloneProgress(detectorProgress)
            progress(mapped.state, mapped.message, mapped.percent)
        }
        val plan = mapStandaloneResult(result)
        val reportPath = persistStandaloneReport(context, result, plan)
        return plan.copy(reportPath = reportPath)
    }
}

internal fun standaloneProfileFor(requestedIntervalMs: Long): DetectorScanProfile = when {
    // Preserve the prototype's explicit fast/accurate compatibility choices.
    requestedIntervalMs == 500L -> DetectorScanProfile.FAST
    requestedIntervalMs == 250L -> DetectorScanProfile.PRECISE
    requestedIntervalMs <= DetectorScanProfile.PRECISE.checkpointIntervalMs -> DetectorScanProfile.PRECISE
    requestedIntervalMs <= DetectorScanProfile.BALANCED.checkpointIntervalMs -> DetectorScanProfile.BALANCED
    else -> DetectorScanProfile.FAST
}

internal fun prototypeProfileLabel(profile: DetectorScanProfile): String = when (profile) {
    DetectorScanProfile.FAST -> "Prototype Fast PTS (30s)"
    DetectorScanProfile.BALANCED -> "Prototype Balanced PTS (10s)"
    DetectorScanProfile.PRECISE -> "Prototype Precise PTS (3s)"
}

internal fun mapStandaloneProgress(progress: DetectionProgress): StandaloneProgress {
    val state = when (progress.phase) {
        "preflight" -> CompilationPipelineState.PREPARING
        "coarse_scan" -> CompilationPipelineState.COARSE_SCAN
        "refining" -> CompilationPipelineState.REFINING
        "completed" -> CompilationPipelineState.BUILDING_CLIP_PLAN
        else -> CompilationPipelineState.PREPARING
    }
    // Scanning occupies 0..54 in the existing worker; export starts at 55.
    val percent = (progress.percent.coerceIn(0, 100) * 54 / 100).coerceIn(0, 54)
    return StandaloneProgress(state, progress.message, percent)
}

internal fun mapStandaloneResult(result: TransitionDetectionResult): StandaloneScanBridgeResult {
    val transitions = result.transitions.sortedBy { it.actualFramePtsMs }
    val timestamps = transitions.map { it.actualFramePtsMs }.distinct()
    val summaries = transitions.map { mark ->
        ScanTransitionSummary(
            eventBoundaryMs = mark.eventBoundaryMs,
            actualFramePtsMs = mark.actualFramePtsMs,
            fromNumber = mark.fromNumber,
            toNumber = mark.toNumber,
            confidence = mark.confidence,
            confirmation = mark.confirmation
        )
    }
    val candidateCount = maxOf(result.metrics.candidateCount, transitions.size)
    return StandaloneScanBridgeResult(
        segments = planExactTransitionSegments(timestamps, result.videoDurationMs),
        videoDurationMs = result.videoDurationMs,
        reportPath = "",
        candidateCount = candidateCount,
        candidateTimestampsMs = timestamps,
        rejectedTransitionCount = (candidateCount - transitions.size).coerceAtLeast(0),
        completedCheckpointCount = result.metrics.checkpointCount,
        transitionSummaries = summaries
    )
}

private fun persistStandaloneReport(
    context: Context,
    result: TransitionDetectionResult,
    plan: StandaloneScanBridgeResult
): String {
    val reportDir = File(context.filesDir, "scan_reports")
    check(reportDir.exists() || reportDir.mkdirs()) { "Could not create scan report directory" }
    val output = File(reportDir, "scan-${System.currentTimeMillis()}.json")
    val temporary = File(reportDir, ".${output.name}.tmp")
    if (temporary.exists()) temporary.delete()
    temporary.writeText(standaloneReportJson(result, plan, System.currentTimeMillis()).toString(2))
    check(temporary.renameTo(output)) { "Could not atomically publish scan report ${output.absolutePath}" }
    return output.absolutePath
}

internal fun standaloneReportJson(
    result: TransitionDetectionResult,
    plan: StandaloneScanBridgeResult,
    savedAtMs: Long
): JSONObject = JSONObject().apply {
    val rejected = plan.rejectedTransitionCount
    put("schemaVersion", result.schemaVersion)
    put("sourceUri", result.sourceUri)
    put("profileLabel", prototypeProfileLabel(result.profile))
    put("scannerMode", ScanMode.StableCheckpoint.name)
    put("frameProvider", "MediaRetrieverFrameSampler")
    put("frameProviderFallbackReason", JSONObject.NULL)
    put("checkpointIntervalMs", result.profile.checkpointIntervalMs)
    putFinite("scanSpeedMultiple", result.metrics.videoToWallSpeed)
    put("experimentalDownscaleSize", 0)
    put("videoDurationMs", result.videoDurationMs)
    put("wallClockScanMs", result.metrics.wallClockMs)
    put("candidateWindows", result.metrics.candidateCount)
    put("coarseSampleCount", result.metrics.checkpointCount)
    put("acceptedTransitions", result.transitions.size)
    put("rejectedCandidates", rejected)
    put("fallbackUsed", false)
    put("failureReason", JSONObject.NULL)
    put("ocrCallCount", result.metrics.ocrInferenceCount)
    put("frameStepMs", result.profile.checkpointIntervalMs)
    put("candidatesFound", result.metrics.candidateCount)
    put("mode", ScanMode.StableCheckpoint.name)
    put("durationMs", result.videoDurationMs)
    put("transitionsFound", result.transitions.size)
    put("scannerVersion", result.metrics.scannerVersion)
    put("metrics", JSONObject().apply {
        put("wallClockMs", result.metrics.wallClockMs)
        put("checkpointCount", result.metrics.checkpointCount)
        put("decodedFrameCount", result.metrics.decodedFrameCount)
        put("ocrInferenceCount", result.metrics.ocrInferenceCount)
        put("candidateCount", result.metrics.candidateCount)
        put("confirmedTransitionCount", result.metrics.confirmedTransitionCount)
        put("acceptedTransitions", result.transitions.size)
        put("rejectedCandidates", rejected)
        putFinite("throughputVideoToWall", result.metrics.videoToWallSpeed)
        put("scanRate20xGateMet", result.metrics.videoToWallSpeed >= 20f)
        put("scanRate30xGateMet", result.metrics.videoToWallSpeed >= 30f)
    })
    put("clipPlan", JSONObject().apply {
        put("policy", "exact-semantic-windows-v1")
        put("resultClassification", if (plan.transitionSummaries.isEmpty()) "NONE" else "CONFIRMED")
        put("clipCount", plan.segments.size)
        put("totalDurationMs", expectedCompilationDurationMs(plan.segments))
        put("transitionSummaries", transitionSummariesJson(plan.transitionSummaries))
        put("segments", JSONArray().apply {
            plan.segments.forEach { segment ->
                put(JSONObject().apply {
                    put("startMs", segment.startMs)
                    put("endMs", segment.endMs)
                    put("durationMs", (segment.endMs - segment.startMs).coerceAtLeast(0L))
                })
            }
        })
    })
    put("candidateConfirmations", JSONArray())
    put("checkpointTimeline", JSONArray().apply {
        result.checkpoints.forEach { put(statePointJson(it)) }
    })
    put("recursiveStateEvidence", JSONArray())
    put("transitionMarks", JSONArray().apply {
        result.transitions.sortedBy { it.actualFramePtsMs }.forEach { mark ->
            put(JSONObject().apply {
                put("eventBoundaryMs", mark.eventBoundaryMs)
                put("actualFramePtsMs", mark.actualFramePtsMs)
                put("fromNumber", mark.fromNumber ?: JSONObject.NULL)
                put("toNumber", mark.toNumber)
                putFinite("confidence", mark.confidence)
                put("confirmation", mark.confirmation)
                put("requestedCutStartMs", (mark.actualFramePtsMs - 10_000L).coerceAtLeast(0L))
                put("requestedCutEndMs", (mark.actualFramePtsMs + 30_000L).coerceAtMost(result.videoDurationMs))
                put("candidateReason", "sparse-checkpoint-pts")
                putFinite("candidatePeakScore", mark.confidence)
                put("evidence", JSONArray().apply { mark.evidence.forEach { put(evidenceJson(it)) } })
            })
        }
    })
    put("scanWindow", JSONObject().apply {
        put("coordinateSpace", "display-upright")
        putFinite("xPercent", result.roi.xFraction)
        putFinite("yPercent", result.roi.yFraction)
        putFinite("widthPercent", result.roi.widthFraction)
        putFinite("heightPercent", result.roi.heightFraction)
    })
    put("warnings", JSONArray(result.warnings))
    put("savedAtMs", savedAtMs)
}

internal fun transitionSummariesJson(summaries: List<ScanTransitionSummary>): JSONArray = JSONArray().apply {
    summaries.forEach { summary ->
        put(JSONObject().apply {
            put("eventBoundaryMs", summary.eventBoundaryMs)
            put("actualFramePtsMs", summary.actualFramePtsMs)
            put("fromNumber", summary.fromNumber ?: JSONObject.NULL)
            put("toNumber", summary.toNumber)
            putFinite("confidence", summary.confidence)
            put("confirmation", summary.confirmation)
        })
    }
}

private fun statePointJson(point: StatePoint): JSONObject = JSONObject().apply {
    put("timeMs", point.timeMs)
    put("source", "coarse-checkpoint")
    put("value", point.value ?: JSONObject.NULL)
    point.evidence?.let { put("evidence", evidenceJson(it)) }
}

private fun evidenceJson(evidence: DigitEvidence): JSONObject = JSONObject().apply {
    put("requestedTimeMs", evidence.requestedTimeMs)
    put("actualFramePtsMs", evidence.actualFramePtsMs ?: JSONObject.NULL)
    put("parsedNumber", evidence.parsedNumber ?: JSONObject.NULL)
    put("rawText", evidence.rawText)
    putFinite("confidence", evidence.confidence)
    put("branch", evidence.branch)
    put("status", evidence.status.name)
    put("elapsedMs", evidence.elapsedMs)
    put("topologyDecision", evidence.topologyDecision ?: JSONObject.NULL)
}
