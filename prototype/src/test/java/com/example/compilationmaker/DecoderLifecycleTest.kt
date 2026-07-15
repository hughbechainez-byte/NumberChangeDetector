package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderLifecycleTest {
    @Test
    fun boundarySampleIsFollowedByExplicitEndOfStream() {
        val boundary = decoderInputAction(
            endOfWindowSampleQueued = false,
            sampleAvailable = true,
            sampleTimeUs = 5_000_000L,
            safeEndUs = 5_000_000L
        )
        val following = decoderInputAction(
            endOfWindowSampleQueued = boundary == DecoderInputAction.QUEUE_BOUNDARY_SAMPLE,
            sampleAvailable = true,
            sampleTimeUs = 5_500_000L,
            safeEndUs = 5_000_000L
        )

        assertEquals(DecoderInputAction.QUEUE_BOUNDARY_SAMPLE, boundary)
        assertEquals(DecoderInputAction.QUEUE_END_OF_STREAM, following)
    }

    @Test
    fun exhaustedExtractorQueuesEndOfStream() {
        assertEquals(
            DecoderInputAction.QUEUE_END_OF_STREAM,
            decoderInputAction(
                endOfWindowSampleQueued = false,
                sampleAvailable = false,
                sampleTimeUs = -1L,
                safeEndUs = 5_000_000L
            )
        )
    }

    @Test
    fun consumerStopTerminatesFrameIteration() {
        assertTrue(shouldStopFrameIteration(FrameIterationDecision.STOP))
        assertFalse(shouldStopFrameIteration(FrameIterationDecision.CONTINUE))
    }
}
