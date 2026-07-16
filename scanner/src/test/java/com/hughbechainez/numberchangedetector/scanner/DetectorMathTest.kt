package com.hughbechainez.numberchangedetector.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DetectorMathTest {
    @Test
    fun oneHourFastProfileUsesOneHundredTwentyOneDirectCheckpoints() {
        val checkpoints = generateCheckpointTimestamps(3_600_000L, ScanProfile.FAST.checkpointIntervalMs)
        assertEquals(121, checkpoints.size)
        assertEquals(0L, checkpoints.first())
        assertEquals(3_600_000L, checkpoints.last())
    }

    @Test
    fun isolatedOcrSpikeIsRemovedBeforeBracketing() {
        val points = listOf(
            StatePoint(0L, 4),
            StatePoint(30_000L, 9),
            StatePoint(60_000L, 4)
        )
        val smoothed = despikeStatePoints(points)
        assertEquals(listOf(4, 4, 4), smoothed.map { it.value })
        assertEquals(listOf(TransitionBracket(0L, 0L, null, 4)), buildTransitionBrackets(smoothed))
    }

    @Test
    fun nullBaselineAndSequentialNumbersCreateExpectedBrackets() {
        val brackets = buildTransitionBrackets(
            listOf(
                StatePoint(0L, null),
                StatePoint(30_000L, 1),
                StatePoint(60_000L, 1),
                StatePoint(90_000L, 2),
                StatePoint(120_000L, 2)
            )
        )
        assertEquals(2, brackets.size)
        assertNull(brackets[0].fromNumber)
        assertEquals(1, brackets[0].toNumber)
        assertEquals(TransitionBracket(60_000L, 90_000L, 1, 2), brackets[1])
    }

    @Test
    fun timestampsRenderWithMilliseconds() {
        assertEquals("00:30.000", formatTimestampMs(30_000L))
        assertEquals("1:07:35.250", formatTimestampMs(4_055_250L))
    }

    @Test
    fun lazyBoundaryPolicyMatchesTheFormerEagerPolicyExhaustively() = runBlocking {
        val alphabet = listOf<Int?>(null, 4, 5)
        val sampleCount = 7
        val combinations = Math.pow(alphabet.size.toDouble(), sampleCount.toDouble()).toInt()
        repeat(combinations) { encoded ->
            var cursor = encoded
            val values = MutableList<Int?>(sampleCount) { null }
            for (index in values.indices) {
                values[index] = alphabet[cursor % alphabet.size]
                cursor /= alphabet.size
            }
            val eager = values.indices.firstOrNull { index ->
                values[index] == 5 &&
                    (((index + 1)..minOf(values.lastIndex, index + 2)).any { next ->
                        values[next] == 5
                    } || index == values.lastIndex)
            }
            val lazy = findFirstPersistentTargetIndex(
                searchStart = 0,
                searchEnd = values.lastIndex,
                sourceLastIndex = values.lastIndex,
                targetNumber = 5,
                valueAt = { values[it] }
            )
            assertEquals("values=$values", eager, lazy)
        }
    }

    @Test
    fun lazyBoundaryStopsImmediatelyAfterPersistenceIsConfirmed() = runBlocking {
        val values = listOf<Int?>(4, 4, 5, 4, 5, 5, 5)
        val requested = mutableListOf<Int>()

        val boundary = findFirstPersistentTargetIndex(
            searchStart = 0,
            searchEnd = values.lastIndex,
            sourceLastIndex = values.lastIndex,
            targetNumber = 5
        ) { index ->
            requested += index
            values[index]
        }

        assertEquals(2, boundary)
        assertEquals(listOf(0, 1, 2, 3, 4), requested)
    }

    @Test
    fun lazyBoundaryRejectsAnIsolatedTargetAndAllowsFinalFrameException() = runBlocking {
        assertNull(
            findFirstPersistentTargetIndex(0, 3, 4, 5) {
                listOf<Int?>(4, 5, 4, 4)[it]
            }
        )
        assertEquals(
            4,
            findFirstPersistentTargetIndex(1, 4, 4, 5) {
                listOf<Int?>(4, 4, 4, 4, 5)[it]
            }
        )
    }
}
