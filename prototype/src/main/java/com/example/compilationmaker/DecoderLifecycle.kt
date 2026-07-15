package com.example.compilationmaker

/** Control returned by frame consumers so a scan budget or cancellation can stop decoding. */
internal enum class FrameIterationDecision {
    CONTINUE,
    STOP
}

/** Pure decoder-input decision used by the MediaCodec provider and regression tests. */
internal enum class DecoderInputAction {
    QUEUE_SAMPLE,
    QUEUE_BOUNDARY_SAMPLE,
    QUEUE_END_OF_STREAM
}

internal fun decoderInputAction(
    endOfWindowSampleQueued: Boolean,
    sampleAvailable: Boolean,
    sampleTimeUs: Long,
    safeEndUs: Long
): DecoderInputAction = when {
    endOfWindowSampleQueued || !sampleAvailable -> DecoderInputAction.QUEUE_END_OF_STREAM
    sampleTimeUs >= safeEndUs -> DecoderInputAction.QUEUE_BOUNDARY_SAMPLE
    else -> DecoderInputAction.QUEUE_SAMPLE
}

internal fun shouldStopFrameIteration(decision: FrameIterationDecision): Boolean =
    decision == FrameIterationDecision.STOP
