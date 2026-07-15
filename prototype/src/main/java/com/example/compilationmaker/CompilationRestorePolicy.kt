package com.example.compilationmaker

/** Android-independent WorkInfo view used when reconciling durable state after recreation. */
internal enum class RestoredWorkState {
    ENQUEUED,
    RUNNING,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

internal data class RestoredWorkSnapshot(
    val workId: String,
    val state: RestoredWorkState,
    val output: OutputVerificationEvidence? = null,
    val errorStage: String = "",
    val errorMessage: String = ""
)

internal enum class CompilationRestoreScreen {
    IDLE,
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    NO_RESULTS,
    MISSING_WORK
}

internal data class CompilationRestoreDecision(
    val screen: CompilationRestoreScreen,
    val workIdToObserve: String?,
    val pipelineState: CompilationPipelineState,
    val sourceUri: String,
    val outputUri: String,
    val outputSizeBytes: Long,
    val outputDurationMs: Long,
    val errorStage: String,
    val message: String,
    val retainPersistedRecord: Boolean
)

internal fun reconcileCompilationRestore(
    persisted: CompilationJobRecord?,
    observed: RestoredWorkSnapshot?,
    persistedOutput: OutputVerificationEvidence? = null
): CompilationRestoreDecision {
    if (persisted == null) {
        return CompilationRestoreDecision(
            screen = CompilationRestoreScreen.IDLE,
            workIdToObserve = null,
            pipelineState = CompilationPipelineState.IDLE,
            sourceUri = "",
            outputUri = "",
            outputSizeBytes = 0L,
            outputDurationMs = 0L,
            errorStage = "",
            message = "Ready",
            retainPersistedRecord = false
        )
    }

    fun decision(
        screen: CompilationRestoreScreen,
        state: CompilationPipelineState,
        message: String,
        workId: String? = persisted.workId,
        output: OutputVerificationEvidence? = null,
        errorStage: String = persisted.errorStage
    ) = CompilationRestoreDecision(
        screen = screen,
        workIdToObserve = workId,
        pipelineState = state,
        sourceUri = persisted.sourceUri,
        outputUri = output?.uri.orEmpty().ifBlank { persisted.outputUri },
        outputSizeBytes = output?.sizeBytes ?: persisted.outputSizeBytes,
        outputDurationMs = output?.durationMs ?: persisted.outputDurationMs,
        errorStage = errorStage,
        message = message,
        retainPersistedRecord = true
    )

    fun successfulState(): CompilationPipelineState =
        if (persisted.previewClassification == CompilationPreviewClassification.PROVISIONAL ||
            persisted.state == CompilationPipelineState.PROVISIONAL_SUCCEEDED
        ) {
            CompilationPipelineState.PROVISIONAL_SUCCEEDED
        } else {
            CompilationPipelineState.SUCCEEDED
        }

    fun successfulMessage(): String =
        if (successfulState() == CompilationPipelineState.PROVISIONAL_SUCCEEDED) {
            "Provisional preview ready"
        } else {
            "Compilation complete"
        }

    if (observed == null) {
        if (persisted.state in setOf(
                CompilationPipelineState.SUCCEEDED,
                CompilationPipelineState.PROVISIONAL_SUCCEEDED
            ) &&
            outputVerificationFailure(persistedOutput) == null
        ) {
            return decision(
                screen = CompilationRestoreScreen.SUCCEEDED,
                state = successfulState(),
                message = successfulMessage(),
                workId = null,
                output = persistedOutput
            )
        }
        return when (persisted.state) {
            CompilationPipelineState.FAILED -> decision(
                CompilationRestoreScreen.FAILED,
                CompilationPipelineState.FAILED,
                persisted.errorMessage.ifBlank { "Compilation failed" },
                workId = null
            )
            CompilationPipelineState.CANCELLED -> decision(
                CompilationRestoreScreen.CANCELLED,
                CompilationPipelineState.CANCELLED,
                "Compilation cancelled",
                workId = null
            )
            CompilationPipelineState.NO_RESULTS -> decision(
                CompilationRestoreScreen.NO_RESULTS,
                CompilationPipelineState.NO_RESULTS,
                persisted.progressMessage.ifBlank { "No transitions detected" },
                workId = null
            )
            else -> decision(
                CompilationRestoreScreen.MISSING_WORK,
                persisted.state,
                "Saved compilation work is missing from WorkManager; diagnostics were retained",
                workId = null,
                errorStage = persisted.stage.ifBlank { "work lookup" }
            )
        }
    }

    if (observed.workId != persisted.workId) {
        return decision(
            CompilationRestoreScreen.MISSING_WORK,
            persisted.state,
            "Persisted work UUID does not match WorkManager",
            workId = null,
            errorStage = "work lookup"
        )
    }

    return when (observed.state) {
        RestoredWorkState.ENQUEUED,
        RestoredWorkState.RUNNING,
        RestoredWorkState.BLOCKED -> decision(
            screen = CompilationRestoreScreen.ACTIVE,
            state = persisted.state.takeIf { it.isActive } ?: CompilationPipelineState.PREPARING,
            message = persisted.progressMessage.ifBlank { "Compilation in progress" }
        )
        RestoredWorkState.SUCCEEDED -> {
            val output = observed.output ?: persistedOutput
            val outputFailure = outputVerificationFailure(output)
            if (outputFailure == null) {
                decision(
                    CompilationRestoreScreen.SUCCEEDED,
                    successfulState(),
                    successfulMessage(),
                    workId = null,
                    output = output
                )
            } else {
                decision(
                    CompilationRestoreScreen.FAILED,
                    CompilationPipelineState.FAILED,
                    outputFailure,
                    workId = null,
                    errorStage = "verification"
                )
            }
        }
        RestoredWorkState.FAILED -> decision(
            CompilationRestoreScreen.FAILED,
            CompilationPipelineState.FAILED,
            observed.errorMessage.ifBlank { persisted.errorMessage.ifBlank { "Compilation failed" } },
            workId = null,
            errorStage = observed.errorStage.ifBlank { persisted.errorStage }
        )
        RestoredWorkState.CANCELLED -> decision(
            CompilationRestoreScreen.CANCELLED,
            CompilationPipelineState.CANCELLED,
            "Compilation cancelled",
            workId = null
        )
    }
}
