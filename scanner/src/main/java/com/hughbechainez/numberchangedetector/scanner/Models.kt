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
    MONOTONIC_3_MIN("Monotonic turbo - adaptive 3 minute skim", 180_000L),
    QUICK_5_MIN("Experimental Quick - adaptive 5 minute skim", 300_000L),
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
    val targetFrameWidthPx: Int = 640,
    val fallbackFrameWidthPx: Int = 640,
    val maxParallelRefinements: Int = 1
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
    MONOTONIC_PLANNING,
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
    suspend fun frameAt(
        timeMs: Long,
        targetWidthPx: Int,
        exactPresentationTime: Boolean = false
    ): FrameSample?
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

internal fun hasIndependentBoundaryConfirmationSamples(
    bracket: TransitionBracket,
    sampleCount: Int
): Boolean = bracket.startMs != bracket.endMs || sampleCount >= 2

internal sealed interface MonotonicTimelinePlan {
    val adaptiveProbeCount: Int

    data class Success(
        val points: List<StatePoint>,
        override val adaptiveProbeCount: Int
    ) : MonotonicTimelinePlan

    data class Fallback(
        val reason: String,
        override val adaptiveProbeCount: Int
    ) : MonotonicTimelinePlan
}

/**
 * Expands sparse monotonic checkpoints only when two observed endpoints skip one or more
 * numeric states. Every returned state was directly observed; missing numbers are never
 * synthesized from arithmetic.
 */
internal suspend fun planMonotonicTimeline(
    checkpoints: List<StatePoint>,
    minProbeSpacingMs: Long = 250L,
    maxAdaptiveProbes: Int = 128,
    maxIntervalJump: Int = 32,
    probe: suspend (Long) -> StatePoint
): MonotonicTimelinePlan {
    if (checkpoints.isEmpty()) return MonotonicTimelinePlan.Fallback("no coarse checkpoints", 0)
    val ordered = checkpoints.sortedBy(StatePoint::timeMs)
    if (ordered.zipWithNext().any { (left, right) -> left.timeMs >= right.timeMs }) {
        return MonotonicTimelinePlan.Fallback("checkpoint times were not strictly increasing", 0)
    }
    if (minProbeSpacingMs <= 0L || maxAdaptiveProbes < 0 || maxIntervalJump < 1) {
        return MonotonicTimelinePlan.Fallback("invalid monotonic planning limits", 0)
    }

    val observed = linkedMapOf<Long, StatePoint>()
    ordered.forEach { observed[it.timeMs] = it }
    var adaptiveProbes = 0
    var failure: String? = null

    fun validNumber(value: Int?): Boolean = value == null || value > 0
    fun rank(value: Int?): Int = value ?: 0

    suspend fun resolve(left: StatePoint, right: StatePoint) {
        if (failure != null) return
        if (!validNumber(left.value) || !validNumber(right.value)) {
            failure = "monotonic states must be null or positive integers"
            return
        }
        if (left.value != null && right.value == null) {
            failure = "counter returned to no-number after counting started"
            return
        }
        val leftRank = rank(left.value)
        val rightRank = rank(right.value)
        if (rightRank < leftRank) {
            failure = "counter decreased or reset from $leftRank to $rightRank"
            return
        }
        val jump = rightRank - leftRank
        if (jump <= 1) return
        if (jump > maxIntervalJump) {
            failure = "counter jump $leftRank->$rightRank exceeded the safety limit"
            return
        }
        val spanMs = right.timeMs - left.timeMs
        if (spanMs <= minProbeSpacingMs) {
            failure = "counter jump $leftRank->$rightRank remained unresolved within ${minProbeSpacingMs}ms"
            return
        }
        if (adaptiveProbes >= maxAdaptiveProbes) {
            failure = "adaptive probe budget was exhausted"
            return
        }
        val midpointMs = left.timeMs + spanMs / 2L
        if (midpointMs <= left.timeMs || midpointMs >= right.timeMs) {
            failure = "adaptive midpoint could not split the interval"
            return
        }

        val midpoint = probe(midpointMs)
        adaptiveProbes++
        if (midpoint.timeMs != midpointMs) {
            failure = "adaptive probe returned the wrong timestamp"
            return
        }
        if (!validNumber(midpoint.value)) {
            failure = "adaptive probe returned a non-positive number"
            return
        }
        val middleRank = rank(midpoint.value)
        if (middleRank !in leftRank..rightRank || (left.value != null && midpoint.value == null)) {
            failure = "adaptive probe violated monotonic endpoint bounds"
            return
        }
        observed[midpointMs] = midpoint
        resolve(left, midpoint)
        resolve(midpoint, right)
    }

    ordered.zipWithNext().forEach { (left, right) -> resolve(left, right) }
    failure?.let { return MonotonicTimelinePlan.Fallback(it, adaptiveProbes) }

    val expanded = observed.values.sortedBy(StatePoint::timeMs)
    if (expanded.none { it.value != null }) {
        return MonotonicTimelinePlan.Fallback("no numeric counter state was observed", adaptiveProbes)
    }
    monotonicTimelineViolation(expanded)?.let {
        return MonotonicTimelinePlan.Fallback(it, adaptiveProbes)
    }
    return MonotonicTimelinePlan.Success(expanded, adaptiveProbes)
}

internal fun monotonicTimelineViolation(points: List<StatePoint>): String? {
    if (points.isEmpty()) return "monotonic timeline was empty"
    if (points.zipWithNext().any { (left, right) -> left.timeMs >= right.timeMs }) {
        return "monotonic timeline timestamps were not strictly increasing"
    }
    var countingStarted = false
    var previousNumber = 0
    for (point in points) {
        val value = point.value
        if (value == null) {
            if (countingStarted) return "counter returned to no-number after counting started"
            continue
        }
        if (value <= 0) return "monotonic states must be positive integers"
        if (!countingStarted) {
            if (value != 1) return "first observed counter value was $value instead of 1"
            countingStarted = true
            previousNumber = value
            continue
        }
        if (value < previousNumber) return "counter decreased or reset from $previousNumber to $value"
        if (value > previousNumber + 1) return "counter skipped $previousNumber->${value}"
        previousNumber = value
    }
    return null
}

internal fun monotonicRefinementViolation(
    brackets: List<TransitionBracket>,
    marks: List<TransitionMark>
): String? {
    if (marks.size != brackets.size) {
        return "confirmed ${marks.size} of ${brackets.size} expected monotonic transitions"
    }
    var previousBoundaryMs = -1L
    brackets.zip(marks).forEach { (bracket, mark) ->
        if (bracket.fromNumber != mark.fromNumber || bracket.toNumber != mark.toNumber) {
            return "refined transition did not match its monotonic bracket"
        }
        val sequential = if (bracket.fromNumber == null) {
            bracket.toNumber == 1
        } else {
            bracket.toNumber == bracket.fromNumber + 1
        }
        if (!sequential) return "monotonic bracket was not an adjacent transition"
        if (mark.actualFramePtsMs !in bracket.startMs..bracket.endMs) {
            return "refined presentation timestamp escaped its monotonic bracket"
        }
        if (mark.eventBoundaryMs <= previousBoundaryMs) {
            return "refined monotonic boundaries were not strictly increasing"
        }
        previousBoundaryMs = mark.eventBoundaryMs
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
