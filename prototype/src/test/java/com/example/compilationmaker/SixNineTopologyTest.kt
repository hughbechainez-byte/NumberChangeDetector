package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

class SixNineTopologyTest {
    @Test
    fun clearSixAndNineUseTwoAgreeingThresholdVariants() {
        assertDecision(SixNineDecision.SIX, glyph(Glyph.SIX), "clear six")
        assertDecision(SixNineDecision.NINE, glyph(Glyph.NINE), "clear nine")
    }

    @Test
    fun zeroSevenEightAndTenCannotProduceSixNineOverride() {
        listOf(Glyph.ZERO, Glyph.SEVEN, Glyph.EIGHT, Glyph.TEN).forEach { glyph ->
            val image = glyph(glyph)
            val evidence = classifySixOrNine(image.pixels, image.width, image.height)
            assertFalse("$glyph was incorrectly overridden: $evidence", evidence.decision == SixNineDecision.SIX)
            assertFalse("$glyph was incorrectly overridden: $evidence", evidence.decision == SixNineDecision.NINE)
        }
    }

    @Test
    fun conservativeClassifierSurvivesBoundedImageVariation() {
        listOf(Glyph.SIX to SixNineDecision.SIX, Glyph.NINE to SixNineDecision.NINE).forEach { (glyph, expected) ->
            val source = glyph(glyph)
            val variants = listOf(
                "noise" to addNoise(source, seed = glyph.ordinal + 41),
                "blur" to blur(source),
                "brighter" to adjust(source, contrast = 1.08f, offset = 14),
                "dimmer" to adjust(source, contrast = 0.86f, offset = -12),
                "translate" to translate(source, dx = 3, dy = -2),
                "scale-down" to scaleAroundCenter(source, 0.90f),
                "scale-up" to scaleAroundCenter(source, 1.08f),
                "dilate" to morphology(source, dilate = true),
                "erode" to morphology(source, dilate = false)
            )
            variants.forEach { (label, image) -> assertDecision(expected, image, "$glyph/$label") }
        }
    }

    @Test
    fun openLoopMultipleHolesNoHoleAndCenterHoleRemainRejected() {
        val openSix = glyph(Glyph.SIX).copyPixels().also { image ->
            for (y in 55..72) for (x in 12..25) image[x, y] = BACKGROUND
        }
        val cases = listOf(
            "open-loop" to openSix,
            "multiple-holes" to glyph(Glyph.EIGHT),
            "no-hole" to glyph(Glyph.SEVEN),
            "center-hole" to glyph(Glyph.ZERO)
        )
        cases.forEach { (label, image) ->
            val evidence = classifySixOrNine(image.pixels, image.width, image.height)
            assertTrue(
                "$label must remain rejected: $evidence",
                evidence.decision == SixNineDecision.AMBIGUOUS || evidence.decision == SixNineDecision.NOT_APPLICABLE
            )
        }
        assertEquals(
            SixNineDecision.AMBIGUOUS,
            classifySixOrNine(glyph(Glyph.ZERO).pixels, WIDTH, HEIGHT).decision
        )
    }

    @Test
    fun invalidDimensionsAreNotApplicable() {
        assertEquals(SixNineDecision.NOT_APPLICABLE, classifySixOrNine(IntArray(8), 3, 3).decision)
        assertEquals(SixNineDecision.NOT_APPLICABLE, classifySixOrNine(IntArray(0), 0, 0).decision)
    }

    @Test
    fun thresholdDisagreementCannotProduceOverride() {
        val composite = LumaImage(136, HEIGHT, IntArray(136 * HEIGHT) { 20 })
        paste(composite, glyph(Glyph.SIX), offsetX = 2, intensity = 240)
        val largerDimNine = morphology(scaleAroundCenter(glyph(Glyph.NINE), 1.08f), dilate = true)
        paste(composite, largerDimNine, offsetX = 70, intensity = 100)

        val evidence = classifySixOrNine(composite.pixels, composite.width, composite.height)
        assertEquals("evidence=$evidence", SixNineDecision.AMBIGUOUS, evidence.decision)
        assertEquals(setOf(SixNineDecision.SIX, SixNineDecision.NINE), evidence.variants.map { it.decision }.toSet())
    }

    @Test
    fun overridePolicyCannotChangeDigitsOutsideSixOrNine() {
        val decisiveNine = classifySixOrNine(glyph(Glyph.NINE).pixels, WIDTH, HEIGHT)
        listOf(1, 2, 3, 4, 5, 7, 8, 10).forEach { value ->
            assertFalse(shouldAdjudicateSixOrNine(value))
            assertEquals(value, adjudicateSixOrNineValue(value, decisiveNine))
        }
        assertEquals(9, adjudicateSixOrNineValue(6, decisiveNine))
        assertEquals(null, adjudicateSixOrNineValue(9, classifySixOrNine(glyph(Glyph.ZERO).pixels, WIDTH, HEIGHT)))
    }

    private fun assertDecision(expected: SixNineDecision, image: LumaImage, label: String) {
        val evidence = classifySixOrNine(image.pixels, image.width, image.height)
        assertEquals("$label evidence=$evidence", expected, evidence.decision)
        assertTrue("$label confidence=${evidence.confidence}", evidence.confidence >= SixNineTopologyPolicy.MIN_OVERRIDE_CONFIDENCE)
        assertEquals(2, evidence.variants.size)
        assertTrue("$label variants=${evidence.variants}", evidence.variants.all { it.decision == expected })
    }

    private fun glyph(type: Glyph): LumaImage {
        val width = if (type == Glyph.TEN) 96 else WIDTH
        val image = LumaImage(width, HEIGHT, IntArray(width * HEIGHT) { BACKGROUND })
        when (type) {
            Glyph.SIX -> {
                drawRing(image, 32f, 63f, 18f, 22f, 0.54f)
                fillRect(image, 14, 18, 25, 64)
            }
            Glyph.NINE -> {
                drawRing(image, 32f, 34f, 18f, 22f, 0.54f)
                fillRect(image, 40, 33, 51, 81)
            }
            Glyph.ZERO -> drawRing(image, 32f, 48f, 18f, 34f, 0.58f)
            Glyph.SEVEN -> {
                fillRect(image, 13, 14, 52, 24)
                drawLine(image, 48, 21, 25, 83, radius = 5)
            }
            Glyph.EIGHT -> {
                drawRing(image, 32f, 30f, 17f, 19f, 0.52f)
                drawRing(image, 32f, 66f, 17f, 19f, 0.52f)
                fillRect(image, 25, 44, 40, 53)
            }
            Glyph.TEN -> {
                fillRect(image, 10, 18, 19, 82)
                drawRing(image, 61f, 48f, 18f, 34f, 0.58f)
            }
        }
        return image
    }

    private fun drawRing(
        image: LumaImage,
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        innerScale: Float
    ) {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = (x - centerX) / radiusX
            val dy = (y - centerY) / radiusY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= 1f && distance >= innerScale) image[x, y] = FOREGROUND
        }
    }

    private fun fillRect(image: LumaImage, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top until bottom) for (x in left until right) {
            if (x in 0 until image.width && y in 0 until image.height) image[x, y] = FOREGROUND
        }
    }

    private fun drawLine(image: LumaImage, x0: Int, y0: Int, x1: Int, y1: Int, radius: Int) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        for (step in 0..steps) {
            val x = (x0 + (x1 - x0) * step.toFloat() / steps).roundToInt()
            val y = (y0 + (y1 - y0) * step.toFloat() / steps).roundToInt()
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (dx * dx + dy * dy <= radius * radius && x + dx in 0 until image.width && y + dy in 0 until image.height) {
                    image[x + dx, y + dy] = FOREGROUND
                }
            }
        }
    }

    private fun addNoise(source: LumaImage, seed: Int): LumaImage {
        val random = Random(seed)
        return LumaImage(source.width, source.height, IntArray(source.pixels.size) { index ->
            (source.pixels[index] + random.nextInt(-24, 25)).coerceIn(0, 255)
        })
    }

    private fun blur(source: LumaImage): LumaImage {
        val output = IntArray(source.pixels.size)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            var sum = 0
            var count = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until source.width && ny in 0 until source.height) {
                    sum += source[nx, ny]
                    count++
                }
            }
            output[y * source.width + x] = sum / count
        }
        return LumaImage(source.width, source.height, output)
    }

    private fun adjust(source: LumaImage, contrast: Float, offset: Int): LumaImage =
        LumaImage(source.width, source.height, IntArray(source.pixels.size) { index ->
            (((source.pixels[index] - 128) * contrast) + 128 + offset).roundToInt().coerceIn(0, 255)
        })

    private fun translate(source: LumaImage, dx: Int, dy: Int): LumaImage {
        val output = LumaImage(source.width, source.height, IntArray(source.pixels.size) { BACKGROUND })
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until source.width && ny in 0 until source.height) output[nx, ny] = source[x, y]
        }
        return output
    }

    private fun scaleAroundCenter(source: LumaImage, scale: Float): LumaImage {
        val output = LumaImage(source.width, source.height, IntArray(source.pixels.size) { BACKGROUND })
        val centerX = (source.width - 1) / 2f
        val centerY = (source.height - 1) / 2f
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val sourceX = ((x - centerX) / scale + centerX).roundToInt()
            val sourceY = ((y - centerY) / scale + centerY).roundToInt()
            if (sourceX in 0 until source.width && sourceY in 0 until source.height) {
                output[x, y] = source[sourceX, sourceY]
            }
        }
        return output
    }

    private fun morphology(source: LumaImage, dilate: Boolean): LumaImage {
        val foreground = BooleanArray(source.pixels.size) { source.pixels[it] > 128 }
        val output = IntArray(source.pixels.size) { BACKGROUND }
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val neighbours = ArrayList<Boolean>(9)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                neighbours += nx in 0 until source.width && ny in 0 until source.height && foreground[ny * source.width + nx]
            }
            val selected = if (dilate) neighbours.any { it } else neighbours.all { it }
            if (selected) output[y * source.width + x] = FOREGROUND
        }
        return LumaImage(source.width, source.height, output)
    }

    private fun paste(target: LumaImage, source: LumaImage, offsetX: Int, intensity: Int) {
        for (y in 0 until source.height) for (x in 0 until source.width) {
            if (source[x, y] > 128 && x + offsetX in 0 until target.width) {
                target[x + offsetX, y] = intensity
            }
        }
    }

    private enum class Glyph { SIX, NINE, ZERO, SEVEN, EIGHT, TEN }

    private data class LumaImage(val width: Int, val height: Int, val pixels: IntArray) {
        operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
        operator fun set(x: Int, y: Int, value: Int) { pixels[y * width + x] = value }
        fun copyPixels(): LumaImage = copy(pixels = pixels.copyOf())
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 96
        const val BACKGROUND = 24
        const val FOREGROUND = 232
    }
}
