package com.example.compilationmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundStatusUiTest {
    private fun snapshot(
        atMs: Long,
        phase: String = "stable scan",
        state: CompilationPipelineState = CompilationPipelineState.COARSE_SCAN,
        message: String = "Checkpoint 1/121 at 00:00.000: none",
        percent: Int = 5
    ) = BackgroundStatusSnapshot(
        phase = phase,
        pipelineState = state,
        workManagerState = "RUNNING",
        message = message,
        percent = percent,
        observedAtMs = atMs
    )

    @Test
    fun conciseModeEmitsStageChangesAndThirtySecondHeartbeats() {
        val first = snapshot(1_000L)
        assertTrue(shouldAppendBackgroundStatus(BackgroundStatusMode.CONCISE, null, first, 0L))
        assertFalse(
            shouldAppendBackgroundStatus(
                BackgroundStatusMode.CONCISE,
                first,
                snapshot(29_999L, percent = 12),
                1_000L
            )
        )
        assertTrue(
            shouldAppendBackgroundStatus(
                BackgroundStatusMode.CONCISE,
                first,
                snapshot(31_000L, percent = 13),
                1_000L
            )
        )
        assertTrue(
            shouldAppendBackgroundStatus(
                BackgroundStatusMode.CONCISE,
                first,
                snapshot(2_000L, phase = "refining", state = CompilationPipelineState.REFINING),
                1_000L
            )
        )
    }

    @Test
    fun coreModeEmitsEveryDistinctWorkerSignal() {
        assertTrue(
            shouldAppendBackgroundStatus(
                BackgroundStatusMode.CORE_ACTIVITY,
                snapshot(1_000L),
                snapshot(1_010L, message = "Checkpoint 2/121 at 00:30.000: 1"),
                1_000L
            )
        )
    }

    @Test
    fun compactAndCoreFormatsRetainExactProgressDetails() {
        val current = snapshot(10L, message = "Refining 1 -> 2 (1/7)", percent = 66)
        val compact = formatBackgroundStatus(current, coreStyle = false)
        val core = formatBackgroundStatus(current, coreStyle = true)
        assertTrue(compact.contains("66%"))
        assertTrue(compact.contains("Refining 1 -> 2 (1/7)"))
        assertTrue(core.contains("worker=RUNNING"))
        assertTrue(core.contains("pipeline=COARSE_SCAN"))
        assertTrue(core.contains("phase=stable scan"))
    }
}
