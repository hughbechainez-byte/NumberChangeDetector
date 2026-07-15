package com.example.compilationmaker

import android.content.Context
import androidx.work.WorkInfo
import org.json.JSONObject

/** Durable, explicit state for the compilation pipeline. */
enum class CompilationPipelineState {
    IDLE,
    VIDEO_SELECTED,
    ROI_SELECTION,
    READY,
    QUEUED,
    PREPARING,
    COARSE_SCAN,
    REFINING,
    FINALIZING,
    BUILDING_CLIP_PLAN,
    EXPORTING,
    VERIFYING,
    SUCCEEDED,
    PROVISIONAL_SUCCEEDED,
    FAILED,
    CANCELLED,
    NO_RESULTS;

    val isActive: Boolean
        get() = this in setOf(
            QUEUED,
            PREPARING,
            COARSE_SCAN,
            REFINING,
            FINALIZING,
            BUILDING_CLIP_PLAN,
            EXPORTING,
            VERIFYING
        )

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, PROVISIONAL_SUCCEEDED, FAILED, CANCELLED, NO_RESULTS)
}

enum class CompilationPreviewClassification {
    NONE,
    CONFIRMED,
    PROVISIONAL
}

data class CompilationJobSettings(
    val scanWindowJson: String = "",
    val scanModeOrdinal: Int = 0,
    val checkpointIntervalMs: Long = 0L,
    val experimentalDownscale: Int = 0,
    val qualityOrdinal: Int = 0,
    val formatOrdinal: Int = 0,
    val transitionStyleOrdinal: Int = 0,
    val videoRotation: Int = 0
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("scanWindowJson", scanWindowJson)
        put("scanModeOrdinal", scanModeOrdinal)
        put("checkpointIntervalMs", checkpointIntervalMs)
        put("experimentalDownscale", experimentalDownscale)
        put("qualityOrdinal", qualityOrdinal)
        put("formatOrdinal", formatOrdinal)
        put("transitionStyleOrdinal", transitionStyleOrdinal)
        put("videoRotation", videoRotation)
    }

    companion object {
        internal fun fromJson(json: JSONObject?): CompilationJobSettings {
            if (json == null) return CompilationJobSettings()
            return CompilationJobSettings(
                scanWindowJson = json.optString("scanWindowJson", ""),
                scanModeOrdinal = json.optInt("scanModeOrdinal", 0),
                checkpointIntervalMs = json.optLong("checkpointIntervalMs", 0L),
                experimentalDownscale = json.optInt("experimentalDownscale", 0),
                qualityOrdinal = json.optInt("qualityOrdinal", 0),
                formatOrdinal = json.optInt("formatOrdinal", 0),
                transitionStyleOrdinal = json.optInt("transitionStyleOrdinal", 0),
                videoRotation = json.optInt("videoRotation", 0)
            )
        }
    }
}

data class CompilationJobRecord(
    val workId: String,
    val uniqueWorkName: String,
    val sourceUri: String,
    val expectedOutputPath: String,
    val state: CompilationPipelineState,
    val stage: String,
    val progressPercent: Int,
    val progressMessage: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long = 0L,
    val outputUri: String = "",
    val outputPath: String = "",
    val outputSizeBytes: Long = 0L,
    val outputDurationMs: Long = 0L,
    val candidateCount: Int = 0,
    val clipCount: Int = 0,
    val previewAvailable: Boolean = false,
    val sourcePermissionPersisted: Boolean = false,
    val sourceDurationMs: Long = 0L,
    val completedCheckpointCount: Int = 0,
    val recursiveProbeCount: Int = 0,
    val semanticLeafCount: Int = 0,
    val confirmedTransitionCount: Int = 0,
    val provisionalTransitionCount: Int = 0,
    val rejectedTransitionCount: Int = 0,
    val scanReportPath: String = "",
    val clipPlanJson: String = "",
    val previewClassification: CompilationPreviewClassification = CompilationPreviewClassification.NONE,
    val fallbackUsed: Boolean = false,
    val fallbackReason: String = "",
    val lastSuccessfulStage: String = "",
    val errorStage: String = "",
    val errorType: String = "",
    val errorMessage: String = "",
    val settings: CompilationJobSettings = CompilationJobSettings()
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", CURRENT_SCHEMA_VERSION)
        put("workId", workId)
        put("uniqueWorkName", uniqueWorkName)
        put("sourceUri", sourceUri)
        put("expectedOutputPath", expectedOutputPath)
        put("state", state.name)
        put("stage", stage)
        put("progressPercent", progressPercent.coerceIn(0, 100))
        put("progressMessage", progressMessage)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
        put("completedAtMs", completedAtMs)
        put("outputUri", outputUri)
        put("outputPath", outputPath)
        put("outputSizeBytes", outputSizeBytes)
        put("outputDurationMs", outputDurationMs)
        put("candidateCount", candidateCount)
        put("clipCount", clipCount)
        put("previewAvailable", previewAvailable)
        put("sourcePermissionPersisted", sourcePermissionPersisted)
        put("sourceDurationMs", sourceDurationMs)
        put("completedCheckpointCount", completedCheckpointCount)
        put("recursiveProbeCount", recursiveProbeCount)
        put("semanticLeafCount", semanticLeafCount)
        put("confirmedTransitionCount", confirmedTransitionCount)
        put("provisionalTransitionCount", provisionalTransitionCount)
        put("rejectedTransitionCount", rejectedTransitionCount)
        put("scanReportPath", scanReportPath)
        put("clipPlanJson", clipPlanJson)
        put("previewClassification", previewClassification.name)
        put("fallbackUsed", fallbackUsed)
        put("fallbackReason", fallbackReason)
        put("lastSuccessfulStage", lastSuccessfulStage)
        put("errorStage", errorStage)
        put("errorType", errorType)
        put("errorMessage", errorMessage)
        put("settings", settings.toJson())
    }

    companion object {
        internal fun fromJson(json: JSONObject): CompilationJobRecord? {
            val workId = json.optString("workId", "")
            val state = runCatching {
                CompilationPipelineState.valueOf(json.optString("state", CompilationPipelineState.IDLE.name))
            }.getOrDefault(CompilationPipelineState.IDLE)
            return CompilationJobRecord(
                workId = workId,
                uniqueWorkName = json.optString("uniqueWorkName", CompilationJobContract.UNIQUE_WORK_NAME),
                sourceUri = json.optString("sourceUri", ""),
                expectedOutputPath = json.optString("expectedOutputPath", ""),
                state = state,
                stage = json.optString("stage", state.name.lowercase()),
                progressPercent = json.optInt("progressPercent", 0).coerceIn(0, 100),
                progressMessage = json.optString("progressMessage", ""),
                createdAtMs = json.optLong("createdAtMs", 0L),
                updatedAtMs = json.optLong("updatedAtMs", 0L),
                completedAtMs = json.optLong("completedAtMs", 0L),
                outputUri = json.optString("outputUri", ""),
                outputPath = json.optString("outputPath", ""),
                outputSizeBytes = json.optLong("outputSizeBytes", 0L),
                outputDurationMs = json.optLong("outputDurationMs", 0L),
                candidateCount = json.optInt("candidateCount", 0),
                clipCount = json.optInt("clipCount", 0),
                previewAvailable = json.optBoolean("previewAvailable", false),
                sourcePermissionPersisted = json.optBoolean("sourcePermissionPersisted", false),
                sourceDurationMs = json.optLong("sourceDurationMs", 0L),
                completedCheckpointCount = json.optInt("completedCheckpointCount", 0),
                recursiveProbeCount = json.optInt("recursiveProbeCount", 0),
                semanticLeafCount = json.optInt("semanticLeafCount", 0),
                confirmedTransitionCount = json.optInt("confirmedTransitionCount", 0),
                provisionalTransitionCount = json.optInt("provisionalTransitionCount", 0),
                rejectedTransitionCount = json.optInt("rejectedTransitionCount", 0),
                scanReportPath = json.optString("scanReportPath", ""),
                clipPlanJson = json.optString("clipPlanJson", ""),
                previewClassification = runCatching {
                    CompilationPreviewClassification.valueOf(
                        json.optString("previewClassification", CompilationPreviewClassification.NONE.name)
                    )
                }.getOrDefault(CompilationPreviewClassification.NONE),
                fallbackUsed = json.optBoolean("fallbackUsed", false),
                fallbackReason = json.optString("fallbackReason", ""),
                lastSuccessfulStage = json.optString("lastSuccessfulStage", ""),
                errorStage = json.optString("errorStage", ""),
                errorType = json.optString("errorType", ""),
                errorMessage = json.optString("errorMessage", ""),
                settings = CompilationJobSettings.fromJson(json.optJSONObject("settings"))
            )
        }

        private const val CURRENT_SCHEMA_VERSION = 2
    }
}

/** Input/output keys shared by MainActivity and CompilationWorker. */
object CompilationJobContract {
    const val UNIQUE_WORK_NAME = "compilation_scan_export"
    const val KEY_EXPECTED_OUTPUT_PATH = "expectedOutputPath"
    const val KEY_PIPELINE_STATE = "pipelineState"
    const val KEY_OUTPUT_URI = "outputUri"
    const val KEY_OUTPUT_SIZE_BYTES = "outputSizeBytes"
    const val KEY_OUTPUT_DURATION_MS = "outputDurationMs"
    const val KEY_ERROR_STAGE = "errorStage"
    const val KEY_ERROR_TYPE = "errorType"
    const val KEY_PREVIEW_CLASSIFICATION = "previewClassification"
    const val KEY_CONFIRMED_TRANSITION_COUNT = "confirmedTransitionCount"
    const val KEY_PROVISIONAL_TRANSITION_COUNT = "provisionalTransitionCount"
}

class CompilationJobStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): CompilationJobRecord? {
        val payload = preferences.getString(KEY_RECORD_JSON, null)
        if (payload != null) {
            return runCatching { CompilationJobRecord.fromJson(JSONObject(payload)) }.getOrNull()
        }

        // v0.17.3 persisted only this UUID. Keep observing that WorkManager job after update
        // instead of silently resetting the Activity to ROI selection.
        val legacyWorkId = preferences.getString(LEGACY_ACTIVE_WORK_ID, null).orEmpty()
        if (legacyWorkId.isBlank()) return null
        val now = System.currentTimeMillis()
        return CompilationJobRecord(
            workId = legacyWorkId,
            uniqueWorkName = CompilationJobContract.UNIQUE_WORK_NAME,
            sourceUri = "",
            expectedOutputPath = "",
            state = CompilationPipelineState.QUEUED,
            stage = "legacy work recovery",
            progressPercent = 0,
            progressMessage = "Recovering active compilation from v0.17.3",
            createdAtMs = now,
            updatedAtMs = now,
            errorStage = "metadata recovery",
            errorType = "LegacyJobMetadata",
            errorMessage = "Only the WorkRequest UUID was available from v0.17.3"
        ).also { recovered ->
            preferences.edit().putString(KEY_RECORD_JSON, recovered.toJson().toString()).commit()
        }
    }

    @Synchronized
    fun save(record: CompilationJobRecord): Boolean {
        val editor = preferences.edit()
            .putString(KEY_RECORD_JSON, record.toJson().toString())
        if (record.workId.isNotBlank() && record.state.isActive) {
            editor.putString(LEGACY_ACTIVE_WORK_ID, record.workId)
        } else {
            editor.remove(LEGACY_ACTIVE_WORK_ID)
        }
        if (record.state == CompilationPipelineState.SUCCEEDED ||
            record.state == CompilationPipelineState.PROVISIONAL_SUCCEEDED
        ) {
            editor.putString(KEY_LAST_SUCCESS_JSON, record.toJson().toString())
        }
        return editor.commit()
    }

    @Synchronized
    fun loadLastSuccess(): CompilationJobRecord? {
        val payload = preferences.getString(KEY_LAST_SUCCESS_JSON, null) ?: return null
        return runCatching { CompilationJobRecord.fromJson(JSONObject(payload)) }.getOrNull()
    }

    @Synchronized
    fun update(workId: String, transform: (CompilationJobRecord) -> CompilationJobRecord): CompilationJobRecord? {
        val current = load() ?: return null
        if (current.workId != workId) return current
        val updated = transform(current).copy(updatedAtMs = System.currentTimeMillis())
        save(updated)
        return updated
    }

    @Synchronized
    fun updateState(
        workId: String,
        state: CompilationPipelineState,
        stage: String,
        message: String,
        percent: Int
    ): CompilationJobRecord? = update(workId) { current ->
        current.copy(
            state = state,
            stage = stage,
            progressMessage = message,
            progressPercent = percent.coerceIn(0, 100),
            completedAtMs = if (state.isTerminal) System.currentTimeMillis() else current.completedAtMs
        )
    }

    companion object {
        const val PREFERENCES_NAME = "compilation_jobs"
        private const val KEY_RECORD_JSON = "current_job_json"
        private const val KEY_LAST_SUCCESS_JSON = "last_successful_job_json"
        private const val LEGACY_ACTIVE_WORK_ID = "active_work_id"
    }
}

enum class CompilationUiChannel { COMPILATION, ROI, UPDATE, LOG, TRANSIENT }

internal fun shouldWriteCompilationProgress(channel: CompilationUiChannel): Boolean =
    channel == CompilationUiChannel.COMPILATION

internal fun isActiveWorkManagerState(state: WorkInfo.State?): Boolean =
    state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING || state == WorkInfo.State.BLOCKED

internal fun shouldInitializeRoi(
    persistedState: CompilationPipelineState?,
    workManagerState: WorkInfo.State?
): Boolean = persistedState in setOf(
    null,
    CompilationPipelineState.IDLE,
    CompilationPipelineState.VIDEO_SELECTED,
    CompilationPipelineState.ROI_SELECTION,
    CompilationPipelineState.READY
) && !isActiveWorkManagerState(workManagerState)

internal fun pipelineStateForProgressPhase(
    phase: String?,
    fallback: CompilationPipelineState = CompilationPipelineState.PREPARING
): CompilationPipelineState {
    val normalized = phase.orEmpty().trim().lowercase()
    return when (normalized) {
        "no_results", "no result", "no results" -> CompilationPipelineState.NO_RESULTS
        "cancelled", "canceled" -> CompilationPipelineState.CANCELLED
        "failed", "error" -> CompilationPipelineState.FAILED
        "provisional_completed", "provisional success", "provisional preview ready" ->
            CompilationPipelineState.PROVISIONAL_SUCCEEDED
        "completed", "success" -> CompilationPipelineState.SUCCEEDED
        "verify", "verify output", "verifying" -> CompilationPipelineState.VERIFYING
        "export", "muxing final output" -> CompilationPipelineState.EXPORTING
        "clip plan", "building clip plan" -> CompilationPipelineState.BUILDING_CLIP_PLAN
        "finalizing" -> CompilationPipelineState.FINALIZING
        "refining", "number confirmation", "ocr confirmation" -> CompilationPipelineState.REFINING
        "stable scan", "experimental scan", "coarse scan" -> CompilationPipelineState.COARSE_SCAN
        "preparing", "starting", "fallback" -> CompilationPipelineState.PREPARING
        "queued", "enqueue" -> CompilationPipelineState.QUEUED
        else -> fallback
    }
}
