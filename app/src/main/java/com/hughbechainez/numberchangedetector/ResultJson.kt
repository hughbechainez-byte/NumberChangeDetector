package com.hughbechainez.numberchangedetector

import com.hughbechainez.numberchangedetector.scanner.DigitEvidence
import com.hughbechainez.numberchangedetector.scanner.StatePoint
import com.hughbechainez.numberchangedetector.scanner.TransitionDetectionResult
import com.hughbechainez.numberchangedetector.scanner.TransitionMark
import org.json.JSONArray
import org.json.JSONObject

fun resultToJson(result: TransitionDetectionResult): String = JSONObject().apply {
    put("schemaVersion", result.schemaVersion)
    put("scannerVersion", result.metrics.scannerVersion)
    put("sourceUri", result.sourceUri)
    put("videoDurationMs", result.videoDurationMs)
    put("profile", result.profile.name)
    put("checkpointIntervalMs", result.profile.checkpointIntervalMs)
    put("roi", JSONObject().apply {
        put("coordinateSpace", "display-upright")
        put("xFraction", result.roi.xFraction)
        put("yFraction", result.roi.yFraction)
        put("widthFraction", result.roi.widthFraction)
        put("heightFraction", result.roi.heightFraction)
    })
    put("transitionMarks", JSONArray().apply {
        result.transitions.forEach { put(markJson(it)) }
    })
    put("checkpoints", JSONArray().apply {
        result.checkpoints.forEach { put(pointJson(it)) }
    })
    put("metrics", JSONObject().apply {
        put("wallClockMs", result.metrics.wallClockMs)
        put("checkpointCount", result.metrics.checkpointCount)
        put("decodedFrameCount", result.metrics.decodedFrameCount)
        put("ocrInferenceCount", result.metrics.ocrInferenceCount)
        put("candidateCount", result.metrics.candidateCount)
        put("confirmedTransitionCount", result.metrics.confirmedTransitionCount)
        put("videoToWallSpeed", result.metrics.videoToWallSpeed.toDouble())
    })
    put("warnings", JSONArray(result.warnings))
}.toString(2)

private fun markJson(mark: TransitionMark): JSONObject = JSONObject().apply {
    put("eventBoundaryMs", mark.eventBoundaryMs)
    put("actualFramePtsMs", mark.actualFramePtsMs)
    put("fromNumber", mark.fromNumber ?: JSONObject.NULL)
    put("toNumber", mark.toNumber)
    put("confidence", mark.confidence.toDouble())
    put("confirmation", mark.confirmation)
    put("evidence", JSONArray().apply { mark.evidence.forEach { put(evidenceJson(it)) } })
}

private fun pointJson(point: StatePoint): JSONObject = JSONObject().apply {
    put("requestedTimeMs", point.timeMs)
    put("parsedNumber", point.value ?: JSONObject.NULL)
    point.evidence?.let { put("evidence", evidenceJson(it)) }
}

private fun evidenceJson(evidence: DigitEvidence): JSONObject = JSONObject().apply {
    put("requestedTimeMs", evidence.requestedTimeMs)
    put("actualFramePtsMs", evidence.actualFramePtsMs ?: JSONObject.NULL)
    put("parsedNumber", evidence.parsedNumber ?: JSONObject.NULL)
    put("rawText", evidence.rawText)
    put("confidence", evidence.confidence.toDouble())
    put("branch", evidence.branch)
    put("status", evidence.status.name)
    put("elapsedMs", evidence.elapsedMs)
    put("topologyDecision", evidence.topologyDecision ?: JSONObject.NULL)
}
