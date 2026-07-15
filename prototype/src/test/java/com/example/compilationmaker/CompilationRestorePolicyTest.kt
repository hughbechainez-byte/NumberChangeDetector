package com.example.compilationmaker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CompilationRestorePolicyTest {
    private val workId = UUID.randomUUID().toString()
    private val validOutput = OutputVerificationEvidence(
        uri = "content://compilations/restored.mp4",
        exists = true,
        canOpen = true,
        sizeBytes = 55_000L,
        metadataReadable = true,
        durationMs = 12_000L,
        hasVideoTrack = true,
        hasReadPermission = true
    )

    private fun record(
        state: CompilationPipelineState = CompilationPipelineState.COARSE_SCAN
    ): CompilationJobRecord {
        val successful = state == CompilationPipelineState.SUCCEEDED ||
            state == CompilationPipelineState.PROVISIONAL_SUCCEEDED
        return CompilationJobRecord(
        workId = workId,
        uniqueWorkName = CompilationJobContract.UNIQUE_WORK_NAME,
        sourceUri = "content://videos/source.mp4",
        expectedOutputPath = "/cache/expected.mp4",
        state = state,
        stage = state.name.lowercase(),
        progressPercent = 43,
        progressMessage = "Scanning timeline",
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        outputUri = if (successful) validOutput.uri.orEmpty() else "",
        outputPath = if (successful) "/cache/restored.mp4" else "",
        outputSizeBytes = if (successful) validOutput.sizeBytes else 0L,
        outputDurationMs = if (successful) validOutput.durationMs else 0L,
        previewAvailable = successful,
        previewClassification = if (state == CompilationPipelineState.PROVISIONAL_SUCCEEDED) {
            CompilationPreviewClassification.PROVISIONAL
        } else if (successful) {
            CompilationPreviewClassification.CONFIRMED
        } else {
            CompilationPreviewClassification.NONE
        }
    )
    }

    @Test
    fun activityDestroyedAndRecreatedDuringScanReconnectsToTheSameUuid() {
        val decision = reconcileCompilationRestore(
            persisted = record(),
            observed = RestoredWorkSnapshot(workId, RestoredWorkState.RUNNING)
        )

        assertEquals(CompilationRestoreScreen.ACTIVE, decision.screen)
        assertEquals(workId, decision.workIdToObserve)
        assertEquals(CompilationPipelineState.COARSE_SCAN, decision.pipelineState)
    }

    @Test
    fun processKilledAndReopenedUsesTheUuidRestoredFromDurableSerialization() {
        val restoredRecord = CompilationJobRecord.fromJson(JSONObject(record().toJson().toString()))
        val decision = reconcileCompilationRestore(
            persisted = restoredRecord,
            observed = RestoredWorkSnapshot(workId, RestoredWorkState.RUNNING)
        )

        assertEquals(workId, decision.workIdToObserve)
        assertEquals("content://videos/source.mp4", decision.sourceUri)
    }

    @Test
    fun appReopenedDuringFinalizationRestoresFinalizationForTheSameWork() {
        val decision = reconcileCompilationRestore(
            persisted = record(CompilationPipelineState.FINALIZING).copy(
                progressMessage = "Finalizing 12 candidates"
            ),
            observed = RestoredWorkSnapshot(workId, RestoredWorkState.RUNNING)
        )

        assertEquals(CompilationRestoreScreen.ACTIVE, decision.screen)
        assertEquals(CompilationPipelineState.FINALIZING, decision.pipelineState)
        assertEquals("Finalizing 12 candidates", decision.message)
    }

    @Test
    fun appReopenedAfterSuccessRestoresVerifiedOutputActions() {
        val decision = reconcileCompilationRestore(
            persisted = record(CompilationPipelineState.SUCCEEDED),
            observed = RestoredWorkSnapshot(workId, RestoredWorkState.SUCCEEDED, validOutput)
        )

        assertEquals(CompilationRestoreScreen.SUCCEEDED, decision.screen)
        assertNull(decision.workIdToObserve)
        assertEquals(validOutput.uri, decision.outputUri)
        assertEquals(validOutput.sizeBytes, decision.outputSizeBytes)
        assertEquals(validOutput.durationMs, decision.outputDurationMs)
    }

    @Test
    fun provisionalPreviewSurvivesRecreationWithoutBecomingConfirmed() {
        val decision = reconcileCompilationRestore(
            persisted = record(CompilationPipelineState.PROVISIONAL_SUCCEEDED),
            observed = RestoredWorkSnapshot(workId, RestoredWorkState.SUCCEEDED, validOutput)
        )

        assertEquals(CompilationRestoreScreen.SUCCEEDED, decision.screen)
        assertEquals(CompilationPipelineState.PROVISIONAL_SUCCEEDED, decision.pipelineState)
        assertEquals("Provisional preview ready", decision.message)
        assertEquals(validOutput.uri, decision.outputUri)
    }

    @Test
    fun cleanedWorkManagerDatabaseStillRestoresAValidPersistedOutput() {
        val decision = reconcileCompilationRestore(
            persisted = record(CompilationPipelineState.SUCCEEDED),
            observed = null,
            persistedOutput = validOutput
        )

        assertEquals(CompilationRestoreScreen.SUCCEEDED, decision.screen)
        assertEquals(validOutput.uri, decision.outputUri)
        assertTrue(decision.retainPersistedRecord)
    }

    @Test
    fun missingWorkInfoRetainsMetadataAndShowsAVisibleDiagnosticState() {
        val persisted = record(CompilationPipelineState.FINALIZING)
        val decision = reconcileCompilationRestore(persisted, observed = null)

        assertEquals(CompilationRestoreScreen.MISSING_WORK, decision.screen)
        assertEquals(persisted.sourceUri, decision.sourceUri)
        assertTrue(decision.retainPersistedRecord)
        assertTrue(decision.message.contains("missing from WorkManager"))
    }

    @Test
    fun failedAndCancelledWorkRemainDistinctVisibleTerminalStates() {
        val failed = reconcileCompilationRestore(
            persisted = record(CompilationPipelineState.EXPORTING),
            observed = RestoredWorkSnapshot(
                workId,
                RestoredWorkState.FAILED,
                errorStage = "export",
                errorMessage = "Muxing failed"
            )
        )
        val cancelled = reconcileCompilationRestore(
            persisted = record(CompilationPipelineState.EXPORTING),
            observed = RestoredWorkSnapshot(workId, RestoredWorkState.CANCELLED)
        )

        assertEquals(CompilationRestoreScreen.FAILED, failed.screen)
        assertEquals("export", failed.errorStage)
        assertEquals("Muxing failed", failed.message)
        assertEquals(CompilationRestoreScreen.CANCELLED, cancelled.screen)
        assertEquals("Compilation cancelled", cancelled.message)
    }

    @Test
    fun workManagerSuccessWithMissingFileRestoresFailureNotSuccess() {
        val decision = reconcileCompilationRestore(
            persisted = record(CompilationPipelineState.VERIFYING),
            observed = RestoredWorkSnapshot(
                workId,
                RestoredWorkState.SUCCEEDED,
                validOutput.copy(exists = false)
            )
        )

        assertEquals(CompilationRestoreScreen.FAILED, decision.screen)
        assertEquals(CompilationPipelineState.FAILED, decision.pipelineState)
        assertEquals("verification", decision.errorStage)
    }
}
