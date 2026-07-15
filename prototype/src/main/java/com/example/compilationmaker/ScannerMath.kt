package com.example.compilationmaker

import kotlin.math.max

data class VisualFallbackCandidate(val timestampMs: Long, val peakScore: Float, val visualChange: Boolean)

data class StableStateVote(
    val timestampMs: Long,
    val value: Int?,
    /** Actual decoded-frame time, retained even when the requested seek is not exact. */
    val decodedTimestampMs: Long? = null,
    /** OCR/decode disposition retained as evidence rather than being collapsed into null. */
    val status: String = if (value == null) "NO_TRANSITION" else "CONFIRMED_TRANSITION",
    val mlKitValue: Int? = value,
    val mlKitConfidence: Float? = null,
    val rawText: String = "",
    val preprocessingBranch: String = "",
    val mlKitStructure: MlKitRecognitionEvidence? = null,
    val topology: GlyphTopologyEvidence? = null,
    val cropMs: Long = 0L,
    val preprocessMs: Long = 0L,
    val ocrMs: Long = 0L
)

data class StableNumberState(
    val value: Int?,
    val stable: Boolean,
    val votes: List<StableStateVote>
)

data class NumberStatePoint(
    val timeMs: Long,
    val value: Int?,
    val stable: Boolean
)

data class SemanticStateInterval(
    val startMs: Long,
    val endMs: Long,
    val fromNumber: Int?,
    val toNumber: Int
)

data class StateIntervalInvestigation(
    val intervals: List<SemanticStateInterval>,
    val probes: Int
)

data class PersistentBoundaryResult(
    val timeMs: Long?,
    val samples: Int
)

/** Finds the earliest persistent target state inside an already classified complete interval. */
suspend fun refinePersistentStateBoundary(
    startMs: Long,
    endMs: Long,
    targetNumber: Int,
    durationMs: Long,
    minSpanMs: Long = 250L,
    maxBinarySamples: Int = 8,
    confirmationStepMs: Long = 250L,
    sample: suspend (Long) -> Int?
): PersistentBoundaryResult {
    var lowMs = startMs.coerceIn(0L, durationMs)
    var highMs = endMs.coerceIn(lowMs, durationMs)
    var samples = 0
    repeat(maxBinarySamples.coerceAtLeast(0)) {
        if (highMs - lowMs <= minSpanMs.coerceAtLeast(1L)) return@repeat
        val midpointMs = lowMs + (highMs - lowMs) / 2L
        val value = sample(midpointMs)
        samples++
        if (value == targetNumber) highMs = midpointMs else lowMs = midpointMs
    }
    val confirmationTimes = listOf(
        highMs,
        highMs + confirmationStepMs,
        highMs + confirmationStepMs * 2L
    ).map { it.coerceIn(0L, durationMs) }.distinct()
    val confirmations = confirmationTimes.map { timeMs ->
        samples++
        sample(timeMs)
    }
    val firstPersistent = confirmations.indices.firstOrNull { index ->
        confirmations[index] == targetNumber &&
            confirmations.drop(index + 1).any { it == targetNumber }
    }
    return PersistentBoundaryResult(firstPersistent?.let(confirmationTimes::get), samples)
}

/**
 * Recursively investigates a complete coarse interval.  Unstable samples remain local evidence;
 * they do not cancel sibling branches or manufacture transitions.
 */
suspend fun investigateStateInterval(
    left: NumberStatePoint,
    right: NumberStatePoint,
    minLeafMs: Long = 2_000L,
    maxDepth: Int = 6,
    maxProbes: Int = 15,
    sample: suspend (Long) -> NumberStatePoint
): StateIntervalInvestigation {
    val leaves = ArrayList<SemanticStateInterval>()
    var probes = 0

    suspend fun recurse(a: NumberStatePoint, b: NumberStatePoint, depth: Int) {
        if (b.timeMs <= a.timeMs) return
        if (a.stable && b.stable && a.value == b.value) return

        val semantic = if (a.stable && b.stable) classifyTransition(a.value, b.value) else null
        val spanMs = b.timeMs - a.timeMs
        if (spanMs <= minLeafMs && a.stable && b.stable) {
            if (semantic?.sequential == true && b.value != null) {
                leaves += SemanticStateInterval(a.timeMs, b.timeMs, a.value, b.value)
            }
            return
        }
        if (depth >= maxDepth || probes >= maxProbes) {
            if (semantic?.sequential == true && b.value != null) {
                leaves += SemanticStateInterval(a.timeMs, b.timeMs, a.value, b.value)
            }
            return
        }

        val midpointMs = a.timeMs + spanMs / 2L
        if (midpointMs <= a.timeMs || midpointMs >= b.timeMs) return
        val midpoint = sample(midpointMs)
        probes++
        recurse(a, midpoint, depth + 1)
        recurse(midpoint, b, depth + 1)
    }

    recurse(left, right, 0)
    val unique = leaves.distinctBy { listOf(it.startMs, it.endMs, it.fromNumber, it.toNumber) }
        .sortedBy { it.startMs }
    return StateIntervalInvestigation(unique, probes)
}

/** Five-sample state voting used by the checkpoint timeline and deterministic tests. */
fun classifyStableNumberState(votes: List<StableStateVote>): StableNumberState {
    val numbers = votes.mapNotNull { it.value }
    val grouped = numbers.groupingBy { it }.eachCount()
    val winner = grouped.maxByOrNull { it.value }
    val competingVotes = grouped.filterKeys { it != winner?.key }.values.maxOrNull() ?: 0
    val stableNumber = winner?.takeIf { it.value >= 3 && competingVotes < 2 }?.key
    val validNullVotes = votes.count {
        it.value == null && (it.status == "NO_TRANSITION" || it.status == "NO_TEXT")
    }
    val stableNull = stableNumber == null && validNullVotes >= 3 && grouped.values.none { it >= 2 }
    return StableNumberState(
        value = stableNumber,
        stable = stableNumber != null || stableNull,
        votes = votes
    )
}

data class AdaptiveVisualThreshold(val median: Float, val mad: Float, val threshold: Float)

fun adaptiveVisualThreshold(scores: List<Float>): AdaptiveVisualThreshold {
    if (scores.isEmpty()) return AdaptiveVisualThreshold(0f, 0f, 8f)
    val sorted = scores.sorted()
    val median = sorted[sorted.size / 2]
    val deviations = scores.map { kotlin.math.abs(it - median) }.sorted()
    val mad = deviations[deviations.size / 2]
    return AdaptiveVisualThreshold(median, mad, (median + 6f * mad).coerceIn(8f, 25f))
}

class IncrementalTransitionLedger(private val dedupeMs: Long) {
    private val confirmed = ArrayList<Long>()
    val size: Int get() = confirmed.size

    fun confirm(timestampMs: Long) {
        val previous = confirmed.lastOrNull()
        if (previous == null || timestampMs - previous > dedupeMs) {
            confirmed += timestampMs
        } else {
            confirmed[confirmed.lastIndex] = minOf(previous, timestampMs)
        }
    }

    fun snapshot(): List<Long> = confirmed.toList()
}

fun selectVisualFallbackTransitions(
    candidates: List<VisualFallbackCandidate>,
    visualThreshold: Float,
    dedupeMs: Long
): List<Long> {
    val qualified = candidates
        .filter { it.visualChange && it.peakScore >= visualThreshold * 1.15f }
        .sortedBy { it.timestampMs }
    val result = ArrayList<Long>()
    for (candidate in qualified) {
        val previous = result.lastOrNull()
        if (previous == null || candidate.timestampMs - previous > dedupeMs) result += candidate.timestampMs
    }
    return result
}

fun generateCheckpointTimestamps(durationMs: Long, intervalMs: Long): List<Long> {
    require(durationMs >= 0L)
    val step = intervalMs.coerceAtLeast(1L)
    val result = ArrayList<Long>()
    var cursor = 0L
    while (cursor < durationMs) {
        result += cursor
        val next = cursor + step
        if (next <= cursor) break
        cursor = next
    }
    val tailMs = durationMs - (result.lastOrNull() ?: 0L)
    // Avoid a redundant near-duration seek (Video A is 3,600,500 ms, so this keeps 61 points).
    if (result.isEmpty() || (result.last() != durationMs && tailMs > 1_000L)) result += durationMs
    return result
}

fun checkpointInvestigationProbeLimit(
    leftStable: Boolean,
    rightStable: Boolean,
    fromNumber: Int?,
    toNumber: Int?
): Int {
    val semanticEndpoints = leftStable && rightStable && fromNumber != toNumber
    return if (semanticEndpoints) 3 else 1
}

data class HysteresisDecision(
    val open: Boolean,
    val consecutiveChanged: Int,
    val consecutiveStable: Int
)

data class TransitionClassification(
    val accepted: Boolean,
    val sequential: Boolean,
    val label: String
)

fun rotateScanWindowForCurrentRotation(
    saved: ScanWindow,
    savedRotation: Int,
    currentRotation: Int
): ScanWindow {
    val from = ((savedRotation % 360) + 360) % 360
    val to = ((currentRotation % 360) + 360) % 360
    val rotated = when ((to - from + 360) % 360) {
        90 -> ScanWindow(saved.yPercent, 1f - saved.xPercent - saved.widthPercent, saved.heightPercent, saved.widthPercent)
        180 -> ScanWindow(1f - saved.xPercent - saved.widthPercent, 1f - saved.yPercent - saved.heightPercent, saved.widthPercent, saved.heightPercent)
        270 -> ScanWindow(1f - saved.yPercent - saved.heightPercent, saved.xPercent, saved.heightPercent, saved.widthPercent)
        else -> saved
    }
    return coerceScanWindow(rotated)
}

fun coerceScanWindow(window: ScanWindow): ScanWindow {
    val x = window.xPercent.coerceIn(0f, 1f)
    val y = window.yPercent.coerceIn(0f, 1f)
    val w = window.widthPercent.coerceIn(0.01f, max(0.01f, 1f - x))
    val h = window.heightPercent.coerceIn(0.01f, max(0.01f, 1f - y))
    return ScanWindow(x, y, w, h)
}

fun mergeSegmentWindows(input: List<SegmentWindow>, maxGapMs: Long): List<SegmentWindow> {
    if (input.isEmpty()) return emptyList()
    val merged = ArrayList<SegmentWindow>()
    val sorted = input.sortedBy { it.startMs }
    var current = sorted.first()
    for (index in 1 until sorted.size) {
        val next = sorted[index]
        current = if (next.startMs <= current.endMs + maxGapMs) {
            SegmentWindow(current.startMs, max(current.endMs, next.endMs))
        } else {
            merged.add(current)
            next
        }
    }
    merged.add(current)
    return merged
}

fun buildRequestedSegment(
    boundaryMs: Long,
    durationMs: Long,
    preRollMs: Long = 10_000L,
    postRollMs: Long = 30_000L
): SegmentWindow {
    val safeBoundary = boundaryMs.coerceAtLeast(0L)
    val safeDuration = durationMs.coerceAtLeast(0L)
    return SegmentWindow(
        startMs = max(0L, safeBoundary - preRollMs),
        endMs = minOf(safeDuration, safeBoundary + postRollMs)
    )
}

/** Builds the semantic clip plan without UI-style padding or arbitrary gap merging. */
fun planExactTransitionSegments(
    boundariesMs: List<Long>,
    durationMs: Long,
    preRollMs: Long = 10_000L,
    postRollMs: Long = 30_000L
): List<SegmentWindow> = mergeSegmentWindows(
    boundariesMs.map { boundaryMs ->
        buildRequestedSegment(boundaryMs, durationMs, preRollMs, postRollMs)
    },
    maxGapMs = 0L
)

fun expectedCompilationDurationMs(segments: List<SegmentWindow>): Long =
    segments.sumOf { segment -> (segment.endMs - segment.startMs).coerceAtLeast(0L) }

enum class TransitionPlanClassification {
    CONFIRMED,
    PROVISIONAL_HIGH,
    PROVISIONAL_MEDIUM,
    REJECTED,
    INCONCLUSIVE
}

data class ProvisionalTransitionEvidence(
    val timestampMs: Long,
    val visualScore: Float,
    val fromNumber: Int? = null,
    val toNumber: Int? = null,
    val fromStateStable: Boolean = false,
    val toStateStable: Boolean = false,
    val partialEvidence: Boolean = false,
    val timeoutCount: Int = 0,
    val reason: String = ""
)

data class PlannedTransitionPoint(
    val timestampMs: Long,
    val classification: TransitionPlanClassification,
    val confidence: Float,
    val visualScore: Float,
    val reason: String
)

fun classifyProvisionalTransition(evidence: ProvisionalTransitionEvidence): PlannedTransitionPoint? {
    if (evidence.timestampMs < 0L || !evidence.visualScore.isFinite() || evidence.visualScore <= 0f) return null
    val semantic = if (evidence.fromStateStable && evidence.toStateStable) {
        classifyTransition(evidence.fromNumber, evidence.toNumber)
    } else {
        null
    }
    if (semantic != null && !semantic.sequential) return null
    val classification = if (semantic?.sequential == true) {
        TransitionPlanClassification.PROVISIONAL_HIGH
    } else {
        TransitionPlanClassification.PROVISIONAL_MEDIUM
    }
    val confidence = when (classification) {
        TransitionPlanClassification.PROVISIONAL_HIGH -> if (evidence.partialEvidence) 0.82f else 0.76f
        TransitionPlanClassification.PROVISIONAL_MEDIUM -> if (evidence.partialEvidence) 0.62f else 0.52f
        else -> 0f
    }
    val detail = buildList {
        add(evidence.reason.ifBlank { "retained visual candidate" })
        semantic?.label?.let { add("state=$it") }
        if (evidence.timeoutCount > 0) add("innerTimeouts=${evidence.timeoutCount}")
        if (evidence.partialEvidence) add("partialEvidence=true")
    }.joinToString("; ")
    return PlannedTransitionPoint(
        timestampMs = evidence.timestampMs,
        classification = classification,
        confidence = confidence,
        visualScore = evidence.visualScore,
        reason = detail
    )
}

/**
 * Confirmed marks always win. High-confidence provisional marks may supplement them; medium
 * visual marks are used only when strict confirmation produced no preview at all.
 */
fun selectTransitionPlanPoints(
    confirmedTimestampsMs: List<Long>,
    provisionalEvidence: List<ProvisionalTransitionEvidence>,
    dedupeToleranceMs: Long
): List<PlannedTransitionPoint> {
    val confirmed = confirmedTimestampsMs.map { timestampMs ->
        PlannedTransitionPoint(
            timestampMs = timestampMs,
            classification = TransitionPlanClassification.CONFIRMED,
            confidence = 1f,
            visualScore = Float.POSITIVE_INFINITY,
            reason = "strict semantic confirmation"
        )
    }
    val provisional = provisionalEvidence.mapNotNull(::classifyProvisionalTransition)
        .filter { point ->
            confirmed.isEmpty() || point.classification == TransitionPlanClassification.PROVISIONAL_HIGH
        }
    val priority = mapOf(
        TransitionPlanClassification.CONFIRMED to 3,
        TransitionPlanClassification.PROVISIONAL_HIGH to 2,
        TransitionPlanClassification.PROVISIONAL_MEDIUM to 1
    )
    val selected = ArrayList<PlannedTransitionPoint>()
    (confirmed + provisional)
        .sortedWith(
            compareByDescending<PlannedTransitionPoint> { priority[it.classification] ?: 0 }
                .thenByDescending { it.visualScore }
                .thenBy { it.timestampMs }
        )
        .forEach { candidate ->
            if (selected.none { existing ->
                    kotlin.math.abs(existing.timestampMs - candidate.timestampMs) <= dedupeToleranceMs.coerceAtLeast(0L)
                }
            ) {
                selected += candidate
            }
        }
    return selected.sortedBy { it.timestampMs }
}

data class TransitionPlanSelection(
    val points: List<PlannedTransitionPoint>,
    val baselineFallbackUsed: Boolean
)

fun selectTransitionPlanWithBaselineFallback(
    confirmedTimestampsMs: List<Long>,
    refinedEvidence: List<ProvisionalTransitionEvidence>,
    baselineEvidence: List<ProvisionalTransitionEvidence>,
    dedupeToleranceMs: Long
): TransitionPlanSelection {
    val primary = selectTransitionPlanPoints(
        confirmedTimestampsMs = confirmedTimestampsMs,
        provisionalEvidence = refinedEvidence,
        dedupeToleranceMs = dedupeToleranceMs
    )
    if (confirmedTimestampsMs.isNotEmpty() || primary.isNotEmpty()) {
        return TransitionPlanSelection(primary, baselineFallbackUsed = false)
    }
    val baseline = selectTransitionPlanPoints(
        confirmedTimestampsMs = emptyList(),
        provisionalEvidence = baselineEvidence,
        dedupeToleranceMs = dedupeToleranceMs
    )
    return TransitionPlanSelection(baseline, baselineFallbackUsed = baseline.isNotEmpty())
}

fun classifyTransition(before: Int?, after: Int?): TransitionClassification {
    return when {
        after == null -> TransitionClassification(false, false, "unconfirmed")
        before == null && after == 1 -> TransitionClassification(true, true, "null -> 1")
        before == null -> TransitionClassification(true, false, "unknown -> $after")
        before + 1 == after -> TransitionClassification(true, true, "$before -> $after")
        before == after -> TransitionClassification(false, false, "$before unchanged")
        else -> TransitionClassification(true, false, "$before -> $after")
    }
}

@Suppress("UNUSED_PARAMETER")
fun resolveSourceDurationMs(sourceDurationMs: Long, processingDurationMs: Long): Long {
    return sourceDurationMs.coerceAtLeast(0L)
}

fun updateHysteresisDecision(
    previous: HysteresisDecision,
    changed: Boolean,
    openThreshold: Int = 2,
    closeThreshold: Int = 2
): HysteresisDecision {
    val nextChanged = if (changed) previous.consecutiveChanged + 1 else 0
    val nextStable = if (changed) 0 else previous.consecutiveStable + 1
    val open = when {
        previous.open && nextStable >= closeThreshold -> false
        !previous.open && nextChanged >= openThreshold -> true
        else -> previous.open
    }
    return HysteresisDecision(open, nextChanged, nextStable)
}
