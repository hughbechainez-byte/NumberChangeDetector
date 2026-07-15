package com.example.compilationmaker

import kotlin.math.ceil
import kotlin.math.max

enum class SixNineDecision { SIX, NINE, AMBIGUOUS, NOT_APPLICABLE }

data class RectLike(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height
}

data class GlyphTopologyVariantEvidence(
    val decision: SixNineDecision,
    val thresholdMethod: String,
    val thresholdValue: Int,
    val polarity: String,
    val componentArea: Int,
    val componentBounds: RectLike?,
    val holeCount: Int,
    val dominantHoleArea: Int,
    val dominantHoleCentroidYNormalized: Float?,
    val confidence: Float,
    val structurallyValid: Boolean,
    val reason: String
)

data class GlyphTopologyEvidence(
    val decision: SixNineDecision,
    val thresholdMethod: String,
    val thresholdValue: Int,
    val polarity: String,
    val componentArea: Int,
    val componentBounds: RectLike?,
    val holeCount: Int,
    val dominantHoleArea: Int,
    val dominantHoleCentroidYNormalized: Float?,
    val confidence: Float,
    val reason: String,
    val variants: List<GlyphTopologyVariantEvidence> = emptyList()
)

object SixNineTopologyPolicy {
    const val NINE_MAX_HOLE_CENTROID_Y = 0.42f
    const val SIX_MIN_HOLE_CENTROID_Y = 0.58f
    const val MIN_OVERRIDE_CONFIDENCE = 0.85f
}

fun shouldAdjudicateSixOrNine(mlKitValue: Int?): Boolean = mlKitValue == 6 || mlKitValue == 9

/** Returns the unchanged non-6/9 value, a decisive topology override, or null for 6/9 ambiguity. */
fun adjudicateSixOrNineValue(mlKitValue: Int?, topology: GlyphTopologyEvidence?): Int? {
    if (!shouldAdjudicateSixOrNine(mlKitValue)) return mlKitValue
    if (topology == null || topology.confidence < SixNineTopologyPolicy.MIN_OVERRIDE_CONFIDENCE) return null
    return when (topology.decision) {
        SixNineDecision.SIX -> 6
        SixNineDecision.NINE -> 9
        SixNineDecision.AMBIGUOUS, SixNineDecision.NOT_APPLICABLE -> null
    }
}

private data class ForegroundComponent(
    val id: Int,
    val area: Int,
    val bounds: RectLike,
    val borderPixels: Int,
    val touchedSides: Int
)

private data class EnclosedHole(
    val area: Int,
    val centroidY: Float
)

/**
 * Pure deterministic classifier for a bright 6/9 glyph. It never receives sequence context,
 * expected timestamps, or ML Kit state; callers decide whether the complete OCR integer is 6/9.
 */
fun classifySixOrNine(luma: IntArray, width: Int, height: Int): GlyphTopologyEvidence {
    if (width <= 0 || height <= 0 || width.toLong() * height.toLong() != luma.size.toLong()) {
        return emptyTopologyEvidence("invalid luma dimensions")
    }
    if (luma.isEmpty()) return emptyTopologyEvidence("empty luma buffer")

    val normalized = IntArray(luma.size) { index -> luma[index].coerceIn(0, 255) }
    val average = normalized.sumOf(Int::toLong).div(normalized.size).toInt().coerceIn(0, 255)
    val thresholds = listOf(
        "otsu" to otsuThreshold(normalized),
        "average" to average
    )
    val variants = thresholds.map { (method, threshold) ->
        classifyThresholdVariant(normalized, width, height, method, threshold)
    }
    val representative = variants
        .filter { it.structurallyValid }
        .maxWithOrNull(compareBy<GlyphTopologyVariantEvidence> { it.confidence }.thenBy { it.componentArea })
        ?: variants.maxByOrNull { it.componentArea }

    val decisive = variants.filter { it.decision == SixNineDecision.SIX || it.decision == SixNineDecision.NINE }
    val decision = when {
        decisive.size == variants.size && decisive.map { it.decision }.distinct().size == 1 -> decisive.first().decision
        variants.all { it.decision == SixNineDecision.NOT_APPLICABLE } -> SixNineDecision.NOT_APPLICABLE
        else -> SixNineDecision.AMBIGUOUS
    }
    val confidence = if (decision == SixNineDecision.SIX || decision == SixNineDecision.NINE) {
        decisive.minOf { it.confidence }
    } else {
        0f
    }
    val reason = when (decision) {
        SixNineDecision.SIX, SixNineDecision.NINE ->
            "otsu and average thresholds agree on ${decision.name.lowercase()}"
        SixNineDecision.NOT_APPLICABLE -> "no threshold variant found one plausible enclosed hole"
        SixNineDecision.AMBIGUOUS -> "threshold variants did not produce the same decisive topology"
    }

    return GlyphTopologyEvidence(
        decision = decision,
        thresholdMethod = "otsu+average",
        thresholdValue = representative?.thresholdValue ?: -1,
        polarity = "bright-foreground",
        componentArea = representative?.componentArea ?: 0,
        componentBounds = representative?.componentBounds,
        holeCount = representative?.holeCount ?: 0,
        dominantHoleArea = representative?.dominantHoleArea ?: 0,
        dominantHoleCentroidYNormalized = representative?.dominantHoleCentroidYNormalized,
        confidence = confidence,
        reason = reason,
        variants = variants
    )
}

private fun classifyThresholdVariant(
    luma: IntArray,
    width: Int,
    height: Int,
    method: String,
    threshold: Int
): GlyphTopologyVariantEvidence {
    val mask = BooleanArray(luma.size) { index -> luma[index] > threshold }
    val (labels, components) = labelForegroundComponents(mask, width, height)
    val component = components
        .asSequence()
        .filter { isPlausibleDigitComponent(it, width, height) }
        .maxByOrNull { it.area }
        ?: return emptyVariant(method, threshold, "no plausible bright foreground component")

    val holes = findEnclosedHoles(component, labels, width)
    val minimumHoleArea = max(3, ceil(component.bounds.area * 0.015).toInt())
    val qualifying = holes.filter { it.area >= minimumHoleArea }.sortedByDescending { it.area }
    val dominant = qualifying.firstOrNull()
    if (qualifying.size != 1 || dominant == null) {
        return GlyphTopologyVariantEvidence(
            decision = SixNineDecision.NOT_APPLICABLE,
            thresholdMethod = method,
            thresholdValue = threshold,
            polarity = "bright-foreground",
            componentArea = component.area,
            componentBounds = component.bounds,
            holeCount = qualifying.size,
            dominantHoleArea = dominant?.area ?: 0,
            dominantHoleCentroidYNormalized = dominant?.let {
                ((it.centroidY - component.bounds.top) / component.bounds.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            },
            confidence = 0f,
            structurallyValid = false,
            reason = "expected exactly one enclosed hole; found ${qualifying.size}"
        )
    }

    val holeFraction = dominant.area.toFloat() / component.bounds.area.coerceAtLeast(1)
    if (holeFraction !in 0.02f..0.42f) {
        return GlyphTopologyVariantEvidence(
            decision = SixNineDecision.NOT_APPLICABLE,
            thresholdMethod = method,
            thresholdValue = threshold,
            polarity = "bright-foreground",
            componentArea = component.area,
            componentBounds = component.bounds,
            holeCount = 1,
            dominantHoleArea = dominant.area,
            dominantHoleCentroidYNormalized = null,
            confidence = 0f,
            structurallyValid = false,
            reason = "enclosed-hole area fraction $holeFraction is implausible"
        )
    }

    val normalizedY = ((dominant.centroidY - component.bounds.top) /
        component.bounds.height.coerceAtLeast(1)).coerceIn(0f, 1f)
    val decision = when {
        normalizedY <= SixNineTopologyPolicy.NINE_MAX_HOLE_CENTROID_Y -> SixNineDecision.NINE
        normalizedY >= SixNineTopologyPolicy.SIX_MIN_HOLE_CENTROID_Y -> SixNineDecision.SIX
        else -> SixNineDecision.AMBIGUOUS
    }
    val separation = when (decision) {
        SixNineDecision.NINE ->
            ((SixNineTopologyPolicy.NINE_MAX_HOLE_CENTROID_Y - normalizedY) / 0.20f).coerceIn(0f, 1f)
        SixNineDecision.SIX ->
            ((normalizedY - SixNineTopologyPolicy.SIX_MIN_HOLE_CENTROID_Y) / 0.20f).coerceIn(0f, 1f)
        else -> 0f
    }
    val confidence = if (decision == SixNineDecision.SIX || decision == SixNineDecision.NINE) {
        0.85f + 0.15f * separation
    } else {
        0f
    }
    return GlyphTopologyVariantEvidence(
        decision = decision,
        thresholdMethod = method,
        thresholdValue = threshold,
        polarity = "bright-foreground",
        componentArea = component.area,
        componentBounds = component.bounds,
        holeCount = 1,
        dominantHoleArea = dominant.area,
        dominantHoleCentroidYNormalized = normalizedY,
        confidence = confidence,
        structurallyValid = true,
        reason = when (decision) {
            SixNineDecision.NINE -> "single enclosed hole is above the rejection band"
            SixNineDecision.SIX -> "single enclosed hole is below the rejection band"
            SixNineDecision.AMBIGUOUS -> "single enclosed hole is inside the center rejection band"
            SixNineDecision.NOT_APPLICABLE -> "not applicable"
        }
    )
}

private fun labelForegroundComponents(
    mask: BooleanArray,
    width: Int,
    height: Int
): Pair<IntArray, List<ForegroundComponent>> {
    val labels = IntArray(mask.size) { -1 }
    val queue = IntArray(mask.size)
    val components = ArrayList<ForegroundComponent>()
    val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
    val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

    for (seed in mask.indices) {
        if (!mask[seed] || labels[seed] >= 0) continue
        val id = components.size
        var head = 0
        var tail = 0
        queue[tail++] = seed
        labels[seed] = id
        var area = 0
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var borderPixels = 0
        var sideMask = 0

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            area++
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) borderPixels++
            if (x == 0) sideMask = sideMask or 1
            if (x == width - 1) sideMask = sideMask or 2
            if (y == 0) sideMask = sideMask or 4
            if (y == height - 1) sideMask = sideMask or 8

            for (direction in dx.indices) {
                val nx = x + dx[direction]
                val ny = y + dy[direction]
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (mask[next] && labels[next] < 0) {
                    labels[next] = id
                    queue[tail++] = next
                }
            }
        }
        components += ForegroundComponent(
            id = id,
            area = area,
            bounds = RectLike(minX, minY, maxX + 1, maxY + 1),
            borderPixels = borderPixels,
            touchedSides = Integer.bitCount(sideMask)
        )
    }
    return labels to components
}

private fun isPlausibleDigitComponent(component: ForegroundComponent, width: Int, height: Int): Boolean {
    val roiArea = width * height
    val bounds = component.bounds
    if (bounds.width < 4 || bounds.height < 6 || bounds.area <= 0) return false
    if (component.area < max(10, roiArea / 3_000)) return false
    if (component.area > roiArea * 0.68f || bounds.area > roiArea * 0.82f) return false
    val fill = component.area.toFloat() / bounds.area
    if (fill !in 0.08f..0.82f) return false
    val aspect = bounds.width.toFloat() / bounds.height
    if (aspect !in 0.18f..1.85f) return false
    val borderFraction = component.borderPixels.toFloat() / component.area.coerceAtLeast(1)
    if (borderFraction > 0.30f || (component.touchedSides >= 2 && borderFraction > 0.08f)) return false
    return true
}

private fun findEnclosedHoles(
    component: ForegroundComponent,
    labels: IntArray,
    imageWidth: Int
): List<EnclosedHole> {
    val bounds = component.bounds
    val localWidth = bounds.width
    val localHeight = bounds.height
    val size = localWidth * localHeight
    if (size <= 0) return emptyList()
    val outside = BooleanArray(size)
    val visitedHole = BooleanArray(size)
    val queue = IntArray(size)

    fun selected(localIndex: Int): Boolean {
        val x = localIndex % localWidth + bounds.left
        val y = localIndex / localWidth + bounds.top
        return labels[y * imageWidth + x] == component.id
    }

    fun floodOutside(seed: Int) {
        if (seed !in 0 until size || outside[seed] || selected(seed)) return
        var head = 0
        var tail = 0
        queue[tail++] = seed
        outside[seed] = true
        while (head < tail) {
            val current = queue[head++]
            val x = current % localWidth
            val y = current / localWidth
            val neighbours = intArrayOf(current - 1, current + 1, current - localWidth, current + localWidth)
            for ((direction, next) in neighbours.withIndex()) {
                if (direction == 0 && x == 0 || direction == 1 && x == localWidth - 1 ||
                    direction == 2 && y == 0 || direction == 3 && y == localHeight - 1
                ) continue
                if (next in 0 until size && !outside[next] && !selected(next)) {
                    outside[next] = true
                    queue[tail++] = next
                }
            }
        }
    }

    for (x in 0 until localWidth) {
        floodOutside(x)
        floodOutside((localHeight - 1) * localWidth + x)
    }
    for (y in 0 until localHeight) {
        floodOutside(y * localWidth)
        floodOutside(y * localWidth + localWidth - 1)
    }

    val holes = ArrayList<EnclosedHole>()
    for (seed in 0 until size) {
        if (selected(seed) || outside[seed] || visitedHole[seed]) continue
        var head = 0
        var tail = 0
        queue[tail++] = seed
        visitedHole[seed] = true
        var area = 0
        var sumY = 0L
        while (head < tail) {
            val current = queue[head++]
            val x = current % localWidth
            val y = current / localWidth
            area++
            sumY += y + bounds.top
            val neighbours = intArrayOf(current - 1, current + 1, current - localWidth, current + localWidth)
            for ((direction, next) in neighbours.withIndex()) {
                if (direction == 0 && x == 0 || direction == 1 && x == localWidth - 1 ||
                    direction == 2 && y == 0 || direction == 3 && y == localHeight - 1
                ) continue
                if (next in 0 until size && !selected(next) && !outside[next] && !visitedHole[next]) {
                    visitedHole[next] = true
                    queue[tail++] = next
                }
            }
        }
        holes += EnclosedHole(area, sumY.toFloat() / area.coerceAtLeast(1))
    }
    return holes
}

private fun otsuThreshold(luma: IntArray): Int {
    val histogram = IntArray(256)
    luma.forEach { histogram[it]++ }
    val total = luma.size.toLong().coerceAtLeast(1L)
    var weightedTotal = 0L
    for (value in histogram.indices) weightedTotal += value.toLong() * histogram[value]
    var backgroundWeight = 0L
    var backgroundSum = 0L
    var bestVariance = -1.0
    var bestThreshold = 0
    for (threshold in 0..254) {
        backgroundWeight += histogram[threshold]
        if (backgroundWeight == 0L) continue
        val foregroundWeight = total - backgroundWeight
        if (foregroundWeight == 0L) break
        backgroundSum += threshold.toLong() * histogram[threshold]
        val backgroundMean = backgroundSum.toDouble() / backgroundWeight
        val foregroundMean = (weightedTotal - backgroundSum).toDouble() / foregroundWeight
        val variance = backgroundWeight.toDouble() * foregroundWeight.toDouble() *
            (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
        if (variance > bestVariance) {
            bestVariance = variance
            bestThreshold = threshold
        }
    }
    return bestThreshold
}

private fun emptyVariant(method: String, threshold: Int, reason: String) = GlyphTopologyVariantEvidence(
    decision = SixNineDecision.NOT_APPLICABLE,
    thresholdMethod = method,
    thresholdValue = threshold,
    polarity = "bright-foreground",
    componentArea = 0,
    componentBounds = null,
    holeCount = 0,
    dominantHoleArea = 0,
    dominantHoleCentroidYNormalized = null,
    confidence = 0f,
    structurallyValid = false,
    reason = reason
)

private fun emptyTopologyEvidence(reason: String) = GlyphTopologyEvidence(
    decision = SixNineDecision.NOT_APPLICABLE,
    thresholdMethod = "none",
    thresholdValue = -1,
    polarity = "bright-foreground",
    componentArea = 0,
    componentBounds = null,
    holeCount = 0,
    dominantHoleArea = 0,
    dominantHoleCentroidYNormalized = null,
    confidence = 0f,
    reason = reason
)
