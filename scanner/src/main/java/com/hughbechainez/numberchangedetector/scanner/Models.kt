package com.hughbechainez.numberchangedetector.scanner

import android.graphics.Bitmap
import android.net.Uri

data class ScanWindow(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float
) {
    fun normalized(): ScanWindow {
        val x = xFraction.coerceIn(0f, 0.99f)
        val y = yFraction.coerceIn(0f, 0.99f)
        return ScanWindow(
            x,
            y,
            widthFraction.coerceIn(0.01f, 1f - x),
            heightFraction.coerceIn(0.01f, 1f - y)
        )
    }
}

enum class CornerPreset(val label: String, val window: ScanWindow) {
    BOTTOM_LEFT("Bottom left", ScanWindow(0f, 0.70f, 0.28f, 0.30f)),
    BOTTOM_RIGHT("Bottom right", ScanWindow(0.72f, 0.70f, 0.28f, 0.30f)),
    TOP_LEFT("Top left", ScanWindow(0f, 0f, 0.28f, 0.30f)),
    TOP_RIGHT("Top right", ScanWindow(0.72f, 0f, 0.28f, 0.30f));
}

enum class ScanProfile(val label: String, val checkpointIntervalMs: Long) {
    FAST("Fast - 30 second skim", 30_000L),
    BALANCED("Balanced - 10 second skim", 10_000L),
    PRECISE("Precise - 3 second skim", 3_000L)
}

data class SourceVideoMetadata(
    val durationMs: Long,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val rotationDegrees: Int,
    val frameRate: Float?
)

enum class DigitRecognitionStatus {
    PARSED,
    NO_TEXT,
    NO_VALID_INTEGER,
    TIMEOUT,
    OCR_FAILURE,
    INVALID_FRAME
}

data class DigitRecognition(
    val value: Int?,
    val rawText: String,
    val confidence: Float,
    val branch: String,
    val status: DigitRecognitionStatus,
    val elapsedMs: Long,
    val topologyDecision: SixNineDecision? = null,
    val bounds: RecognitionBounds? = null
)

data class DigitEvidence(
    val requestedTimeMs: Long,
    val actualFramePtsMs: Long?,
    val parsedNumber: Int?,
    val rawText: String,
    val confidence: Float,
    val branch: String,
    val status: DigitRecognitionStatus,
    val elapsedMs: Long,
    val topologyDecision: String? = null
)

data class StatePoint(
    val timeMs: Long,
    val value: Int?,
    val evidence: DigitEvidence? = null
)

data class TransitionBracket(
    val startMs: Long,
    val endMs: Long,
    val fromNumber: Int?,
    val toNumber: Int
)

data class TransitionMark(
    val eventBoundaryMs: Long,
    val actualFramePtsMs: Long,
    val fromNumber: Int?,
    val toNumber: Int,
    val confidence: Float,
    val confirmation: String = "CONFIRMED_TRANSITION",
    val evidence: List<DigitEvidence>
)

data class ScanMetrics(
    val scannerVersion: String,
    val wallClockMs: Long,
    val checkpointCount: Int,
    val decodedFrameCount: Int,
    val ocrInferenceCount: Int,
    val candidateCount: Int,
    val confirmedTransitionCount: Int,
    val videoToWallSpeed: Float
)

data class TransitionDetectionRequest(
    val sourceUri: Uri,
    val roi: ScanWindow,
    val profile: ScanProfile,
    val targetFrameWidthPx: Int = 640
)

data class TransitionDetectionResult(
    val schemaVersion: Int = 1,
    val sourceUri: String,
    val videoDurationMs: Long,
    val roi: ScanWindow,
    val profile: ScanProfile,
    val transitions: List<TransitionMark>,
    val checkpoints: List<StatePoint>,
    val metrics: ScanMetrics,
    val warnings: List<String>
)

data class DetectionProgress(val phase: String, val message: String, val percent: Int)

enum class CoreActivityStage {
    FRAME_FETCH,
    PREPROCESS,
    OCR,
    PTS_ENUMERATION,
    BINARY_SEARCH,
    PTS_CONFIRMATION
}

data class CoreActivityEvent(
    val stage: CoreActivityStage,
    val action: String,
    val detail: String,
    val requestedTimeMs: Long? = null,
    val actualFramePtsMs: Long? = null
)

data class FrameSample(
    val requestedTimeMs: Long,
    val presentationTimeMs: Long?,
    val bitmap: Bitmap
) : AutoCloseable {
    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

interface FrameSampler : AutoCloseable {
    val metadata: SourceVideoMetadata
    suspend fun frameAt(timeMs: Long, targetWidthPx: Int): FrameSample?
    fun presentationTimesBetween(startMs: Long, endMs: Long): List<Long>
}

interface DigitRecognizer : AutoCloseable {
    suspend fun recognize(bitmap: Bitmap, aggressive: Boolean = false): DigitRecognition
    val inferenceCount: Int
}

interface TransitionDetector {
    suspend fun detect(
        request: TransitionDetectionRequest,
        onProgress: suspend (DetectionProgress) -> Unit = {}
    ): TransitionDetectionResult
}

fun generateCheckpointTimestamps(durationMs: Long, intervalMs: Long): List<Long> {
    require(durationMs >= 0L)
    val step = intervalMs.coerceAtLeast(1L)
    val result = ArrayList<Long>()
    var timeMs = 0L
    while (timeMs < durationMs) {
        result += timeMs
        if (Long.MAX_VALUE - timeMs < step) break
        timeMs += step
    }
    val tailMs = durationMs - (result.lastOrNull() ?: 0L)
    if (result.isEmpty() || (result.last() != durationMs && tailMs > 1_000L)) result += durationMs
    return result
}

fun despikeStatePoints(points: List<StatePoint>): List<StatePoint> {
    if (points.size < 3) return points
    return points.mapIndexed { index, point ->
        if (index == 0 || index == points.lastIndex) point
        else {
            val before = points[index - 1].value
            val after = points[index + 1].value
            if (before == after && point.value != before) point.copy(value = before) else point
        }
    }
}

fun buildTransitionBrackets(points: List<StatePoint>): List<TransitionBracket> {
    if (points.isEmpty()) return emptyList()
    val result = ArrayList<TransitionBracket>()
    var currentNumber: Int? = null
    var lastCurrentTime = points.first().timeMs
    var lastBaselineTime = points.first().timeMs

    for (point in points) {
        val value = point.value
        if (value == null) {
            if (currentNumber == null) lastBaselineTime = point.timeMs
            continue
        }
        if (currentNumber == null) {
            result += TransitionBracket(lastBaselineTime, point.timeMs, null, value)
            currentNumber = value
            lastCurrentTime = point.timeMs
        } else if (value == currentNumber) {
            lastCurrentTime = point.timeMs
        } else {
            result += TransitionBracket(lastCurrentTime, point.timeMs, currentNumber, value)
            currentNumber = value
            lastCurrentTime = point.timeMs
        }
    }
    return result
}

/**
 * Finds the earliest target sample that is persistent under the scanner's existing rule:
 * the target must be seen again in either of the next two samples, except that the final
 * source sample may stand alone. Samples are requested lazily so work after the first
 * confirmed boundary is never decoded or sent through OCR.
 */
internal suspend fun findFirstPersistentTargetIndex(
    searchStart: Int,
    searchEnd: Int,
    sourceLastIndex: Int,
    targetNumber: Int,
    valueAt: suspend (Int) -> Int?
): Int? {
    if (searchStart > searchEnd) return null
    for (index in searchStart..searchEnd) {
        if (valueAt(index) != targetNumber) continue
        if (index == sourceLastIndex) return index

        val confirmationEnd = minOf(searchEnd, index + 2)
        for (next in (index + 1)..confirmationEnd) {
            if (valueAt(next) == targetNumber) return index
        }
    }
    return null
}

fun formatTimestampMs(timeMs: Long): String {
    val safe = timeMs.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L
    return if (hours > 0L) {
        "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    } else {
        "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}
