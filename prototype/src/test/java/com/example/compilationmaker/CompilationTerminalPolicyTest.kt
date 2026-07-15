package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilationTerminalPolicyTest {
    private val validOutput = OutputVerificationEvidence(
        uri = "content://compilation/output.mp4",
        exists = true,
        canOpen = true,
        sizeBytes = 1_024L,
        metadataReadable = true,
        durationMs = 42_000L,
        hasVideoTrack = true,
        hasReadPermission = true
    )

    @Test
    fun scanBudgetReachedWithValidCandidatesCanOnlySucceedWithVerifiedOutput() {
        val decision = decidePipelineTerminalOutcome(
            candidateCount = 12,
            clipCount = 5,
            output = validOutput
        )

        assertEquals(PipelineTerminalKind.SUCCESS, decision.kind)
        assertEquals(PipelineTerminalStage.VERIFYING, decision.stage)
        assertEquals(validOutput.uri, decision.outputUri)
        assertEquals(validOutput.sizeBytes, decision.outputSizeBytes)
        assertEquals(validOutput.durationMs, decision.outputDurationMs)
    }

    @Test
    fun verifiedFallbackOutputHasDedicatedProvisionalSuccessOutcome() {
        val decision = decidePipelineTerminalOutcome(
            candidateCount = 19,
            clipCount = 10,
            output = validOutput,
            provisional = true
        )

        assertEquals(PipelineTerminalKind.PROVISIONAL_SUCCESS, decision.kind)
        assertEquals(PipelineTerminalStage.VERIFYING, decision.stage)
        assertEquals("Provisional preview ready", decision.message)
        assertEquals(validOutput.uri, decision.outputUri)
    }

    @Test
    fun scanBudgetReachedWithZeroCandidatesReturnsTypedNoResults() {
        val decision = decidePipelineTerminalOutcome(
            candidateCount = 0,
            clipCount = 0,
            output = null
        )

        assertEquals(PipelineTerminalKind.NO_RESULTS, decision.kind)
        assertEquals(PipelineTerminalStage.FINALIZING, decision.stage)
        assertEquals("No transitions detected before scan budget expired", decision.message)
    }

    @Test
    fun finalizationExceptionReturnsFailureAndPreservesTheCompleteCause() {
        val cause = IllegalStateException("candidate merge failed")
        val decision = decidePipelineTerminalOutcome(
            candidateCount = 12,
            clipCount = 0,
            output = null,
            failure = PipelineStageFailure(PipelineTerminalStage.FINALIZING, cause)
        )

        assertEquals(PipelineTerminalKind.FAILURE, decision.kind)
        assertEquals(PipelineTerminalStage.FINALIZING, decision.stage)
        assertSame(cause, decision.cause)
    }

    @Test
    fun exportExceptionReturnsFailureAndPreservesTheCompleteCause() {
        val cause = IllegalArgumentException("muxer rejected sample")
        val decision = decidePipelineTerminalOutcome(
            candidateCount = 12,
            clipCount = 5,
            output = null,
            failure = PipelineStageFailure(PipelineTerminalStage.EXPORTING, cause)
        )

        assertEquals(PipelineTerminalKind.FAILURE, decision.kind)
        assertEquals(PipelineTerminalStage.EXPORTING, decision.stage)
        assertSame(cause, decision.cause)
    }

    @Test
    fun successfulResultWithMissingFileIsRejected() {
        val decision = decidePipelineTerminalOutcome(
            candidateCount = 12,
            clipCount = 5,
            output = validOutput.copy(exists = false)
        )

        assertEquals(PipelineTerminalKind.FAILURE, decision.kind)
        assertEquals(PipelineTerminalStage.VERIFYING, decision.stage)
        assertTrue(decision.message.contains("does not exist"))
    }

    @Test
    fun everyInvalidOutputConditionPreventsSuccess() {
        val invalid = listOf(
            null,
            validOutput.copy(uri = ""),
            validOutput.copy(canOpen = false),
            validOutput.copy(sizeBytes = 0L),
            validOutput.copy(metadataReadable = false),
            validOutput.copy(durationMs = 0L),
            validOutput.copy(hasVideoTrack = false),
            validOutput.copy(hasReadPermission = false)
        )

        invalid.forEach { evidence ->
            assertEquals(
                PipelineTerminalKind.FAILURE,
                decidePipelineTerminalOutcome(1, 1, evidence).kind
            )
        }
    }

    @Test
    fun finalizationTimeoutIsFailureWhileUserCancellationIsCancelled() {
        val timeout = java.util.concurrent.TimeoutException("number confirmation timed out")
        val timedOut = decidePipelineTerminalOutcome(
            candidateCount = 12,
            clipCount = 0,
            output = null,
            failure = PipelineStageFailure(PipelineTerminalStage.FINALIZING, timeout)
        )
        val cancelled = decidePipelineTerminalOutcome(
            candidateCount = 12,
            clipCount = 0,
            output = null,
            failure = PipelineStageFailure(PipelineTerminalStage.FINALIZING, timeout),
            userCancelled = true
        )

        assertEquals(PipelineTerminalKind.FAILURE, timedOut.kind)
        assertEquals(PipelineTerminalKind.CANCELLED, cancelled.kind)
        assertEquals("Compilation cancelled", cancelled.message)
    }

    @Test
    fun retryIsReservedForAnExplicitlyTransientFailure() {
        val permanent = decidePipelineTerminalOutcome(
            candidateCount = 1,
            clipCount = 0,
            output = null,
            failure = PipelineStageFailure(
                PipelineTerminalStage.EXPORTING,
                IllegalStateException("bad muxer configuration")
            )
        )
        val transient = decidePipelineTerminalOutcome(
            candidateCount = 1,
            clipCount = 0,
            output = null,
            failure = PipelineStageFailure(
                PipelineTerminalStage.EXPORTING,
                java.io.IOException("temporary storage unavailable"),
                isTransient = true
            )
        )

        assertEquals(PipelineTerminalKind.FAILURE, permanent.kind)
        assertEquals(PipelineTerminalKind.RETRY, transient.kind)
    }
}
