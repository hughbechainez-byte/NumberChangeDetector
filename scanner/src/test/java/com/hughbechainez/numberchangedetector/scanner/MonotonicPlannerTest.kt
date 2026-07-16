package com.hughbechainez.numberchangedetector.scanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicPlannerTest {
    @Test
    fun threeMinutePlanRecoversEveryObservedFixtureStateWithThreeAdaptiveProbes() = runBlocking {
        val boundaries = listOf(
            30_000L to 1,
            75_000L to 2,
            255_000L to 3,
            855_000L to 4,
            975_000L to 5,
            1_275_000L to 6,
            1_395_000L to 7,
            1_995_000L to 8,
            2_415_000L to 9,
            3_560_000L to 10
        )
        fun valueAt(timeMs: Long): Int? = boundaries.lastOrNull { it.first <= timeMs }?.second
        val coarse = generateCheckpointTimestamps(
            3_600_000L,
            ScanProfile.MONOTONIC_3_MIN.checkpointIntervalMs
        ).map { StatePoint(it, valueAt(it)) }

        val plan = planMonotonicTimeline(coarse) { timeMs ->
            StatePoint(timeMs, valueAt(timeMs))
        }

        assertTrue(plan is MonotonicTimelinePlan.Success)
        val success = plan as MonotonicTimelinePlan.Success
        assertEquals(3, success.adaptiveProbeCount)
        assertEquals(listOf<Int?>(null) + (1..10), success.points.map { it.value }.distinct())
        val brackets = buildTransitionBrackets(success.points)
        assertEquals(10, brackets.size)
        assertEquals((1..10).toList(), brackets.map { it.toNumber })
        assertTrue(brackets.all { bracket ->
            bracket.fromNumber?.let { from -> bracket.toNumber == from + 1 } ?: true
        })
    }

    @Test
    fun equalEndpointsRequireNoAdaptiveWork() = runBlocking {
        var probes = 0
        val plan = planMonotonicTimeline(
            listOf(StatePoint(0L, 1), StatePoint(180_000L, 1))
        ) { timeMs ->
            probes++
            StatePoint(timeMs, 1)
        }

        assertTrue(plan is MonotonicTimelinePlan.Success)
        assertEquals(0, plan.adaptiveProbeCount)
        assertEquals(0, probes)
    }

    @Test
    fun allNoNumberCheckpointsDemandTheProvenFallback() = runBlocking {
        val plan = planMonotonicTimeline(
            listOf(StatePoint(0L, null), StatePoint(180_000L, null))
        ) { timeMs -> StatePoint(timeMs, null) }

        assertTrue(plan is MonotonicTimelinePlan.Fallback)
        assertTrue((plan as MonotonicTimelinePlan.Fallback).reason.contains("no numeric"))
    }

    @Test
    fun invalidMonotonicIntervalsDemandFallback() = runBlocking {
        suspend fun fallbackFor(
            points: List<StatePoint>,
            minSpacingMs: Long = 250L,
            maxProbes: Int = 128,
            probedValue: Int? = 2
        ): MonotonicTimelinePlan = planMonotonicTimeline(
            checkpoints = points,
            minProbeSpacingMs = minSpacingMs,
            maxAdaptiveProbes = maxProbes
        ) { timeMs -> StatePoint(timeMs, probedValue) }

        assertTrue(fallbackFor(listOf(StatePoint(0L, 2), StatePoint(1_000L, 1))) is MonotonicTimelinePlan.Fallback)
        assertTrue(fallbackFor(listOf(StatePoint(0L, 1), StatePoint(1_000L, null))) is MonotonicTimelinePlan.Fallback)
        assertTrue(fallbackFor(listOf(StatePoint(0L, 1), StatePoint(1_000L, 100))) is MonotonicTimelinePlan.Fallback)
        assertTrue(
            fallbackFor(
                listOf(StatePoint(0L, 1), StatePoint(1_000L, 3)),
                probedValue = 4
            ) is MonotonicTimelinePlan.Fallback
        )
        assertTrue(
            fallbackFor(
                listOf(StatePoint(0L, 1), StatePoint(200L, 3)),
                minSpacingMs = 250L
            ) is MonotonicTimelinePlan.Fallback
        )
        assertTrue(
            fallbackFor(
                listOf(StatePoint(0L, 1), StatePoint(1_000L, 3)),
                maxProbes = 0
            ) is MonotonicTimelinePlan.Fallback
        )
    }

    @Test
    fun timelineValidatorMatchesThePersistentIncreasingContractExhaustively() {
        val alphabet = listOf<Int?>(null, 1, 2, 3)
        repeat(256) { encoded ->
            var cursor = encoded
            val values = MutableList<Int?>(4) { null }
            for (index in values.indices) {
                values[index] = alphabet[cursor % alphabet.size]
                cursor /= alphabet.size
            }
            var started = false
            var previous = 0
            var expectedValid = true
            values.forEach { value ->
                if (value == null) {
                    if (started) expectedValid = false
                } else if (!started) {
                    if (value != 1) expectedValid = false
                    started = true
                    previous = value
                } else {
                    if (value < previous || value > previous + 1) expectedValid = false
                    previous = value
                }
            }
            val points = values.mapIndexed { index, value -> StatePoint(index.toLong(), value) }
            assertEquals("values=$values", expectedValid, monotonicTimelineViolation(points) == null)
        }
    }

    @Test
    fun refinementValidatorRejectsMissingWrongAndOutOfOrderMarks() {
        val brackets = listOf(
            TransitionBracket(0L, 100L, null, 1),
            TransitionBracket(100L, 200L, 1, 2)
        )
        val validMarks = listOf(
            mark(50L, null, 1),
            mark(150L, 1, 2)
        )

        assertNull(monotonicRefinementViolation(brackets, validMarks))
        assertTrue(monotonicRefinementViolation(brackets, validMarks.take(1)) != null)
        assertTrue(monotonicRefinementViolation(brackets, listOf(mark(50L, null, 1), mark(150L, 1, 3))) != null)
        assertTrue(monotonicRefinementViolation(brackets, listOf(mark(150L, null, 1), mark(120L, 1, 2))) != null)
        assertTrue(monotonicRefinementViolation(brackets, listOf(mark(50L, null, 1), mark(250L, 1, 2))) != null)
    }

    @Test
    fun firstStateAtVideoStartRequiresAnIndependentConfirmationSample() {
        val zeroWidth = TransitionBracket(0L, 0L, null, 1)
        assertEquals(false, hasIndependentBoundaryConfirmationSamples(zeroWidth, 1))
        assertTrue(hasIndependentBoundaryConfirmationSamples(zeroWidth, 2))
        assertTrue(hasIndependentBoundaryConfirmationSamples(TransitionBracket(0L, 1_000L, null, 1), 1))
    }

    private fun mark(timeMs: Long, from: Int?, to: Int): TransitionMark = TransitionMark(
        eventBoundaryMs = timeMs,
        actualFramePtsMs = timeMs,
        fromNumber = from,
        toNumber = to,
        confidence = 1f,
        evidence = emptyList()
    )
}
