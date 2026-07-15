package com.example.compilationmaker

internal enum class PipelineTerminalKind {
    SUCCESS,
    PROVISIONAL_SUCCESS,
    FAILURE,
    RETRY,
    CANCELLED,
    NO_RESULTS
}

internal enum class PipelineTerminalStage {
    FINALIZING,
    BUILDING_CLIP_PLAN,
    EXPORTING,
    VERIFYING
}

internal data class PipelineStageFailure(
    val stage: PipelineTerminalStage,
    val cause: Throwable,
    val isTransient: Boolean = false
)

/** Evidence collected after export without depending on Android media classes. */
internal data class OutputVerificationEvidence(
    val uri: String?,
    val exists: Boolean,
    val canOpen: Boolean,
    val sizeBytes: Long,
    val metadataReadable: Boolean,
    val durationMs: Long,
    val hasVideoTrack: Boolean,
    val hasReadPermission: Boolean
)

internal data class PipelineTerminalDecision(
    val kind: PipelineTerminalKind,
    val stage: PipelineTerminalStage?,
    val message: String,
    val outputUri: String? = null,
    val outputSizeBytes: Long = 0L,
    val outputDurationMs: Long = 0L,
    val cause: Throwable? = null
)

internal fun outputVerificationFailure(evidence: OutputVerificationEvidence?): String? = when {
    evidence == null -> "Output verification evidence is missing"
    evidence.uri.isNullOrBlank() -> "Output URI is missing"
    !evidence.exists -> "Output does not exist"
    !evidence.canOpen -> "Output cannot be opened"
    evidence.sizeBytes <= 0L -> "Output is empty"
    !evidence.metadataReadable -> "Output metadata is unreadable"
    evidence.durationMs <= 0L -> "Output duration is invalid"
    !evidence.hasVideoTrack -> "Output has no video track"
    !evidence.hasReadPermission -> "Output URI is not readable"
    else -> null
}

/** Resolves exactly one terminal outcome. User cancellation always remains cancellation. */
internal fun decidePipelineTerminalOutcome(
    candidateCount: Int,
    clipCount: Int,
    output: OutputVerificationEvidence?,
    failure: PipelineStageFailure? = null,
    userCancelled: Boolean = false,
    provisional: Boolean = false
): PipelineTerminalDecision {
    if (userCancelled) {
        return PipelineTerminalDecision(
            kind = PipelineTerminalKind.CANCELLED,
            stage = failure?.stage,
            message = "Compilation cancelled",
            cause = failure?.cause
        )
    }

    if (failure != null) {
        return PipelineTerminalDecision(
            kind = if (failure.isTransient) PipelineTerminalKind.RETRY else PipelineTerminalKind.FAILURE,
            stage = failure.stage,
            message = failure.cause.message ?: failure.cause::class.java.simpleName,
            cause = failure.cause
        )
    }

    if (candidateCount <= 0) {
        return PipelineTerminalDecision(
            kind = PipelineTerminalKind.NO_RESULTS,
            stage = PipelineTerminalStage.FINALIZING,
            message = "No transitions detected before scan budget expired"
        )
    }

    if (clipCount <= 0) {
        return PipelineTerminalDecision(
            kind = PipelineTerminalKind.NO_RESULTS,
            stage = PipelineTerminalStage.FINALIZING,
            message = "Scan completed with no usable confirmed or visual transitions"
        )
    }

    val verificationFailure = outputVerificationFailure(output)
    if (verificationFailure != null) {
        return PipelineTerminalDecision(
            kind = PipelineTerminalKind.FAILURE,
            stage = PipelineTerminalStage.VERIFYING,
            message = verificationFailure
        )
    }

    checkNotNull(output)
    return PipelineTerminalDecision(
        kind = if (provisional) PipelineTerminalKind.PROVISIONAL_SUCCESS else PipelineTerminalKind.SUCCESS,
        stage = PipelineTerminalStage.VERIFYING,
        message = if (provisional) "Provisional preview ready" else "Compilation complete",
        outputUri = output.uri,
        outputSizeBytes = output.sizeBytes,
        outputDurationMs = output.durationMs
    )
}
