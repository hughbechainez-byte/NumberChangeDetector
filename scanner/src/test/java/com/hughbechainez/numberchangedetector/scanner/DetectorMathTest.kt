package com.hughbechainez.numberchangedetector.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
