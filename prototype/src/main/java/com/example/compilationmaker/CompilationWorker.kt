package com.example.compilationmaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.system.Os
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CompilationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private var startedAtMs: Long = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        startedAtMs = System.currentTimeMillis()
        val workId = id.toString()
        val jobStore = CompilationJobStore(applicationContext)
        promoteToForeground("starting", "Preparing compilation", 0)?.let {
            jobStore.updateState(workId, CompilationPipelineState.FAILED, "foreground", FOREGROUND_PROMOTION_ERROR, 0)
            AppLog.i(applicationContext, "CompilationWorker", "[worker] returning failure: $FOREGROUND_PROMOTION_ERROR thread=${Thread.currentThread().name}")
            return@withContext it
        }

        val sourceUriRaw = inputData.getString(KEY_SOURCE_URI)
        val qualityOrdinal = inputData.getInt(KEY_QUALITY_ORDINAL, ExportQuality.Medium.ordinal)
        val formatOrdinal = inputData.getInt(KEY_FORMAT_ORDINAL, ExportFormat.Mp4.ordinal)
        val transitionOrdinal = inputData.getInt(KEY_TRANSITION_STYLE_ORDINAL, TransitionStyle.Instant.ordinal)
        val scanModeOrdinal = inputData.getInt(KEY_SCAN_MODE, ScanMode.StableCheckpoint.ordinal)
        val checkpointIntervalMs = inputData.getLong(KEY_CHECKPOINT_INTERVAL_MS, 30_000L)
        val scannerProfileId = inputData.getString(KEY_SCANNER_PROFILE_ID)
        val quickMode = scannerProfileId == QUICK_MODE_PROFILE_ID
        val downscaleSize = inputData.getInt(KEY_EXPERIMENTAL_DOWNSCALE, 32)
        val rotation = inputData.getInt(KEY_VIDEO_ROTATION, 0)
        val scanWindowRaw = inputData.getString(KEY_SCAN_WINDOW)
        val expectedOutputPath = inputData.getString(CompilationJobContract.KEY_EXPECTED_OUTPUT_PATH).orEmpty()
        val expectedOutputFile = expectedOutputPath.takeIf { it.isNotBlank() }?.let(::File)

        if (sourceUriRaw.isNullOrBlank()) {
            val message = "Missing source video"
            jobStore.updateState(workId, CompilationPipelineState.FAILED, "preparing", message, 0)
            AppLog.i(applicationContext, "CompilationWorker", "[worker] returning failure: $message thread=${Thread.currentThread().name}")
            return@withContext Result.failure(workDataOf(
                KEY_ERROR_MESSAGE to message,
                CompilationJobContract.KEY_ERROR_STAGE to "preparing",
                CompilationJobContract.KEY_ERROR_TYPE to "InvalidInput"
            ))
        }

        setProgressCompat("starting", "Preparing compilation", 0)

        val sourceUri = runCatching { Uri.parse(sourceUriRaw) }.getOrNull()
            ?: run {
                val message = "Invalid source URI"
                jobStore.updateState(workId, CompilationPipelineState.FAILED, "preparing", message, 0)
                AppLog.i(applicationContext, "CompilationWorker", "[worker] returning failure: $message thread=${Thread.currentThread().name}")
                return@withContext Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to message,
                    CompilationJobContract.KEY_ERROR_STAGE to "preparing",
                    CompilationJobContract.KEY_ERROR_TYPE to "InvalidInput"
                ))
            }

        val quality = ExportQuality.values().getOrNull(qualityOrdinal) ?: ExportQuality.Medium
        val format = ExportFormat.values().getOrNull(formatOrdinal) ?: ExportFormat.Mp4
        val transitionStyle = TransitionStyle.values().getOrNull(transitionOrdinal) ?: TransitionStyle.Instant
        val scanMode = ScanMode.values().getOrNull(scanModeOrdinal) ?: ScanMode.StableCheckpoint
        val scanWindow = parseScanWindow(scanWindowRaw)
        val engine = VideoCompilationEngine(applicationContext)
        val wakeLock = runCatching {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${applicationContext.packageName}:compilation"
            ).apply {
                setReferenceCounted(false)
                acquire(COMPILATION_WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { failure ->
            AppLog.w(
                applicationContext,
                "CompilationWorker",
                "Could not acquire the foreground compilation CPU wake lock",
                failure
            )
        }.getOrNull()
        var fallbackUsed = false
        var failureReason: String? = null
        var outputForCleanup: File? = expectedOutputFile

        return@withContext try {
            var scanResult = runScanWithMode(
                engine = engine,
                sourceUri = sourceUri,
                scanMode = scanMode,
                scanWindow = scanWindow,
                scanIntervalMs = checkpointIntervalMs,
                scannerProfileId = scannerProfileId,
                rotation = rotation,
                downscaleSize = downscaleSize,
                transitionStyle = transitionStyle
            ) { state, message, percent ->
                val phase = progressPhaseForState(state, scanMode)
                setProgressCompat(phase, message, percent, pipelineState = state)
                setForegroundCompat(phase, message, percent)
                if (!isActive) {
                    throw CancellationException("Job cancelled")
                }
            }

            if (scanMode == ScanMode.Experimental && !scanResult.success) {
                fallbackUsed = true
                failureReason = scanResult.failureReason
                setProgressCompat("fallback", "Experimental mode failed, falling back to stable checkpoint", 50, true)
                setForegroundCompat("fallback", "Experimental mode failed, falling back to stable checkpoint", 50)
                scanResult = runScanWithMode(
                    engine = engine,
                    sourceUri = sourceUri,
                    scanMode = ScanMode.StableCheckpoint,
                    scanWindow = scanWindow,
                    scanIntervalMs = checkpointIntervalMs,
                    scannerProfileId = null,
                    rotation = rotation,
                    downscaleSize = downscaleSize,
                    transitionStyle = transitionStyle
                ) { state, message, percent ->
                    val phase = progressPhaseForState(state, ScanMode.StableCheckpoint)
                    setProgressCompat(phase, message, percent, pipelineState = state)
                    setForegroundCompat(phase, message, percent)
                    if (!isActive) throw CancellationException("Job cancelled")
                }
            }

            if (!scanResult.success) {
                throw scanResult.failure ?: IllegalStateException(scanResult.failureReason ?: "Scan failed")
            }

            if (scanResult.strategyFallbackUsed) {
                fallbackUsed = true
                failureReason = listOfNotNull(
                    failureReason?.takeIf { it.isNotBlank() },
                    scanResult.strategyFallbackReason?.takeIf { it.isNotBlank() }
                ).distinct().joinToString("; ").ifBlank { "The requested scan strategy used its safe fallback" }
            }

            val provisionalPreview =
                scanResult.previewClassification == CompilationPreviewClassification.PROVISIONAL
            if (scanResult.visualFallbackUsed || provisionalPreview) {
                fallbackUsed = true
                val provisionalReason =
                    "Provisional preview uses ${scanResult.provisionalTransitionCount} non-confirmed transition(s) " +
                        "with ${scanResult.confirmedTransitionCount} strict confirmation(s); confirmed-only fixture QA is ineligible"
                failureReason = listOfNotNull(failureReason?.takeIf { it.isNotBlank() }, provisionalReason)
                    .joinToString("; ")
            }

            val reportPath = scanResult.reportPath
            jobStore.update(workId) { record ->
                record.copy(
                    candidateCount = scanResult.candidateCount,
                    clipCount = scanResult.segments.size,
                    sourceDurationMs = scanResult.durationMs,
                    completedCheckpointCount = scanResult.completedCheckpointCount,
                    recursiveProbeCount = scanResult.recursiveProbeCount,
                    semanticLeafCount = scanResult.semanticLeafCount,
                    confirmedTransitionCount = scanResult.confirmedTransitionCount,
                    provisionalTransitionCount = scanResult.provisionalTransitionCount,
                    rejectedTransitionCount = scanResult.rejectedTransitionCount,
                    scanReportPath = reportPath.orEmpty(),
                    clipPlanJson = encodeClipPlan(scanResult),
                    previewClassification = scanResult.previewClassification,
                    fallbackUsed = fallbackUsed,
                    fallbackReason = failureReason.orEmpty(),
                    lastSuccessfulStage = "clip plan"
                )
            }

            val windows = scanResult.segments
            if (windows.isEmpty()) {
                val decision = decidePipelineTerminalOutcome(
                    candidateCount = scanResult.candidateCount,
                    clipCount = 0,
                    output = null
                )
                val message = if (scanResult.candidateCount == 0 && scanResult.scanBudgetReached) {
                    "No transitions detected before scan budget expired"
                } else if (scanResult.candidateCount == 0) {
                    "No transition candidates were detected"
                } else {
                    decision.message
                }
                reportPath?.let { augmentReport(it, fallbackUsed, failureReason ?: message) }
                val terminalState = if (decision.kind == PipelineTerminalKind.NO_RESULTS) {
                    CompilationPipelineState.NO_RESULTS
                } else {
                    CompilationPipelineState.FAILED
                }
                jobStore.updateState(workId, terminalState, decision.stage?.name?.lowercase() ?: "finalizing", message, 100)
                val noResults = decision.kind == PipelineTerminalKind.NO_RESULTS
                jobStore.update(workId) { record ->
                    record.copy(
                        candidateCount = scanResult.candidateCount,
                        clipCount = 0,
                        errorStage = decision.stage?.name?.lowercase().orEmpty(),
                        errorType = decision.kind.name,
                        errorMessage = message
                    )
                }
                val terminalPhase = if (terminalState == CompilationPipelineState.NO_RESULTS) "no_results" else "failed"
                setProgressCompat(terminalPhase, message, 100, fallbackUsed)
                setForegroundCompat(terminalPhase, message, 100)
                AppLog.i(applicationContext, "CompilationWorker", "[worker] returning ${decision.kind.name.lowercase()}: $message thread=${Thread.currentThread().name}")
                return@withContext Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to message,
                        KEY_REPORT_PATH to reportPath.orEmpty(),
                        KEY_FALLBACK_USED to fallbackUsed,
                        KEY_NO_TRANSITIONS_DETECTED to noResults,
                        CompilationJobContract.KEY_PIPELINE_STATE to terminalState.name,
                        CompilationJobContract.KEY_ERROR_STAGE to (decision.stage?.name?.lowercase() ?: "finalizing"),
                        CompilationJobContract.KEY_ERROR_TYPE to decision.kind.name
                    )
                )
            }

            setProgressCompat("export", "Assembling compilation", 55, fallbackUsed)
            setForegroundCompat("export", "Assembling compilation", 55)
            val renderedOutput = engine.renderCompilation(
                sourceUri = sourceUri,
                segments = windows,
                quality = quality,
                format = format,
                transitionStyle = transitionStyle,
                outputFile = expectedOutputFile,
                quickMode = quickMode
            ) { message, percent ->
                val exportPercent = ((percent * 0.35f) + 55f).toInt().coerceIn(55, 95)
                setProgressCompat("export", message, exportPercent, fallbackUsed)
                setForegroundCompat("export", message, exportPercent)
                if (!isActive) throw CancellationException("Job cancelled")
            }
            outputForCleanup = renderedOutput.file

            setProgressCompat("verify", "Verifying compilation output", 96, fallbackUsed)
            setForegroundCompat("verify", "Verifying compilation output", 96)
            val verifiedOutput = engine.verifyCompilationOutput(renderedOutput.file)
            val evidence = OutputVerificationEvidence(
                uri = verifiedOutput.uri,
                exists = verifiedOutput.file.exists(),
                canOpen = verifiedOutput.file.canRead(),
                sizeBytes = verifiedOutput.sizeBytes,
                metadataReadable = verifiedOutput.durationMs > 0L,
                durationMs = verifiedOutput.durationMs,
                hasVideoTrack = true,
                hasReadPermission = verifiedOutput.file.canRead()
            )
            val decision = decidePipelineTerminalOutcome(
                candidateCount = scanResult.candidateCount,
                clipCount = windows.size,
                output = evidence,
                provisional = provisionalPreview
            )
            check(decision.kind == PipelineTerminalKind.SUCCESS ||
                decision.kind == PipelineTerminalKind.PROVISIONAL_SUCCESS
            ) { decision.message }

            reportPath?.let { augmentReport(it, fallbackUsed, failureReason) }

            val finalReportPath = reportPath
            val failure = if (fallbackUsed) failureReason else null
            val terminalState = if (provisionalPreview) {
                CompilationPipelineState.PROVISIONAL_SUCCEEDED
            } else {
                CompilationPipelineState.SUCCEEDED
            }
            val terminalPhase = if (provisionalPreview) "provisional_completed" else "completed"
            val completionMessage = decision.message
            val result = workDataOf(
                KEY_OUTPUT_PATH to verifiedOutput.file.absolutePath,
                CompilationJobContract.KEY_OUTPUT_URI to verifiedOutput.uri,
                CompilationJobContract.KEY_OUTPUT_SIZE_BYTES to verifiedOutput.sizeBytes,
                CompilationJobContract.KEY_OUTPUT_DURATION_MS to verifiedOutput.durationMs,
                CompilationJobContract.KEY_PIPELINE_STATE to terminalState.name,
                CompilationJobContract.KEY_PREVIEW_CLASSIFICATION to scanResult.previewClassification.name,
                CompilationJobContract.KEY_CONFIRMED_TRANSITION_COUNT to scanResult.confirmedTransitionCount,
                CompilationJobContract.KEY_PROVISIONAL_TRANSITION_COUNT to scanResult.provisionalTransitionCount,
                KEY_FORMAT_ORDINAL to format.ordinal,
                KEY_REPORT_PATH to finalReportPath.orEmpty(),
                KEY_FALLBACK_USED to fallbackUsed,
                KEY_ERROR_MESSAGE to failure.orEmpty()
            )
            jobStore.update(workId) { record ->
                record.copy(
                    state = terminalState,
                    stage = if (provisionalPreview) "provisional succeeded" else "succeeded",
                    progressPercent = 100,
                    progressMessage = completionMessage,
                    completedAtMs = System.currentTimeMillis(),
                    outputUri = verifiedOutput.uri,
                    outputPath = verifiedOutput.file.absolutePath,
                    outputSizeBytes = verifiedOutput.sizeBytes,
                    outputDurationMs = verifiedOutput.durationMs,
                    candidateCount = scanResult.candidateCount,
                    clipCount = windows.size,
                    previewAvailable = true,
                    previewClassification = scanResult.previewClassification,
                    fallbackUsed = fallbackUsed,
                    fallbackReason = failureReason.orEmpty(),
                    lastSuccessfulStage = "preview",
                    errorStage = "",
                    errorType = "",
                    errorMessage = ""
                )
            }
            setProgressCompat(terminalPhase, completionMessage, 100, fallbackUsed, terminalState)
            setForegroundCompat(terminalPhase, completionMessage, 100)
            AppLog.i(
                applicationContext,
                "CompilationWorker",
                "[worker] returning success terminalKind=${decision.kind.name} confirmed=${scanResult.confirmedTransitionCount} " +
                    "provisional=${scanResult.provisionalTransitionCount} thread=${Thread.currentThread().name}"
            )
            Result.success(result)
        } catch (cancelled: CancellationException) {
            outputForCleanup?.takeIf { it.exists() }?.let { partial ->
                runCatching {
                    check(partial.delete()) { "Unable to delete partial output ${partial.absolutePath}" }
                }.onFailure {
                    AppLog.w(applicationContext, "CompilationWorker", "Cancellation cleanup failed: ${partial.absolutePath}", it)
                }
            }
            val decision = decidePipelineTerminalOutcome(0, 0, null, userCancelled = true)
            val cancelledAtPercent = jobStore.load()?.takeIf { it.workId == workId }?.progressPercent ?: 0
            jobStore.updateState(workId, CompilationPipelineState.CANCELLED, "cancelled", decision.message, cancelledAtPercent)
            AppLog.i(applicationContext, "CompilationWorker", "[worker] returning cancelled thread=${Thread.currentThread().name}")
            propagateWorkerCancellation(cancelled)
        } catch (e: Exception) {
            val persistedStage = jobStore.load()?.takeIf { it.workId == workId }?.stage.orEmpty().ifBlank { "finalizing" }
            val policyStage = when {
                "verify" in persistedStage -> PipelineTerminalStage.VERIFYING
                "export" in persistedStage || "mux" in persistedStage -> PipelineTerminalStage.EXPORTING
                "clip" in persistedStage || "plan" in persistedStage -> PipelineTerminalStage.BUILDING_CLIP_PLAN
                else -> PipelineTerminalStage.FINALIZING
            }
            val decision = decidePipelineTerminalOutcome(
                candidateCount = 1,
                clipCount = 1,
                output = null,
                failure = PipelineStageFailure(policyStage, e, isTransient = false)
            )
            recordHandledWorkerFailure(applicationContext, "CompilationWorker", "[$persistedStage] Compilation failed", e)
            jobStore.updateState(workId, CompilationPipelineState.FAILED, persistedStage, decision.message, 100)
            jobStore.update(workId) { record ->
                record.copy(errorStage = persistedStage, errorType = e::class.java.name, errorMessage = decision.message)
            }
            AppLog.i(applicationContext, "CompilationWorker", "[worker] returning failure: ${decision.message} thread=${Thread.currentThread().name}")
            Result.failure(workDataOf(
                KEY_ERROR_MESSAGE to decision.message,
                KEY_FALLBACK_USED to fallbackUsed,
                CompilationJobContract.KEY_PIPELINE_STATE to CompilationPipelineState.FAILED.name,
                CompilationJobContract.KEY_ERROR_STAGE to persistedStage,
                CompilationJobContract.KEY_ERROR_TYPE to e::class.java.name
            ))
        } finally {
            engine.close()
            wakeLock?.takeIf { it.isHeld }?.let { heldWakeLock ->
                runCatching { heldWakeLock.release() }
                    .onFailure { AppLog.w(applicationContext, "CompilationWorker", "Wake-lock release failed", it) }
            }
        }
    }

    private suspend fun runScanWithMode(
        engine: VideoCompilationEngine,
        sourceUri: Uri,
        scanMode: ScanMode,
        scanWindow: ScanWindow,
        scanIntervalMs: Long,
        scannerProfileId: String?,
        rotation: Int,
        downscaleSize: Int,
        transitionStyle: TransitionStyle,
        progress: (CompilationPipelineState, String, Int) -> Unit
    ): ScanTaskResult = withContext(Dispatchers.IO) {
        return@withContext try {
            if (scanMode == ScanMode.StableCheckpoint) {
                val result = StandaloneScannerBridge(applicationContext).scan(
                    sourceUri = sourceUri,
                    scanWindow = scanWindow,
                    requestedIntervalMs = scanIntervalMs,
                    requestedProfileId = scannerProfileId,
                    progress = progress,
                    coreActivity = { event -> CoreActivityTelemetry.emit(id.toString(), event) }
                )
                ScanTaskResult(
                    success = true,
                    segments = result.segments,
                    durationMs = result.videoDurationMs,
                    reportPath = result.reportPath,
                    candidateCount = result.candidateCount,
                    candidateTimestampsMs = result.candidateTimestampsMs,
                    scanBudgetReached = false,
                    visualFallbackUsed = false,
                    confirmedTransitionCount = result.transitionSummaries.size,
                    provisionalTransitionCount = 0,
                    rejectedTransitionCount = result.rejectedTransitionCount,
                    completedCheckpointCount = result.completedCheckpointCount,
                    strategyFallbackUsed = result.strategyFallbackUsed,
                    strategyFallbackReason = result.strategyFallbackReason,
                    recursiveProbeCount = 0,
                    semanticLeafCount = result.candidateCount,
                    previewClassification = if (result.transitionSummaries.isEmpty()) {
                        CompilationPreviewClassification.NONE
                    } else {
                        CompilationPreviewClassification.CONFIRMED
                    },
                    transitionSummaries = result.transitionSummaries
                )
            } else {
                val result = engine.findNumberTransitionSegments(
                    sourceUri = sourceUri,
                    frameStepMs = scanIntervalMs,
                    scanMode = scanMode,
                    scanWindow = scanWindow,
                    sourceRotationDegrees = rotation,
                    scanProfileLabel = scanMode.name,
                    transitionStyle = transitionStyle,
                    experimentalDownscaleSize = downscaleSize,
                    fallbackUsed = false
                ) { state, message, percent ->
                    progress(state, message, (percent * 0.5f + 20f).toInt())
                }
                val durationMs = resolveSourceDurationMs(result.videoDurationMs, result.timing.totalMs())
                ScanTaskResult(
                    success = true,
                    segments = result.segments,
                    durationMs = durationMs,
                    reportPath = engine.latestScanReportPath,
                    candidateCount = result.candidateCount,
                    candidateTimestampsMs = result.candidateTimestampsMs,
                    scanBudgetReached = result.scanBudgetReached,
                    visualFallbackUsed = result.visualFallbackUsed,
                    confirmedTransitionCount = result.confirmedTransitionCount,
                    provisionalTransitionCount = result.provisionalTransitionCount,
                    rejectedTransitionCount = result.rejectedTransitionCount,
                    completedCheckpointCount = result.completedCheckpointCount,
                    recursiveProbeCount = result.recursiveProbeCount,
                    semanticLeafCount = result.semanticLeafCount,
                    previewClassification = result.previewClassification,
                    transitionSummaries = result.transitionMarks.map { mark ->
                        ScanTransitionSummary(
                            eventBoundaryMs = mark.eventBoundaryMs,
                            actualFramePtsMs = mark.eventBoundaryMs,
                            fromNumber = mark.fromNumber,
                            toNumber = mark.toNumber,
                            confidence = mark.confidence,
                            confirmation = "CONFIRMED_TRANSITION"
                        )
                    }
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            recordHandledWorkerFailure(applicationContext, "CompilationWorker", "Scan stage failed", e)
            ScanTaskResult(
                success = false,
                segments = emptyList(),
                durationMs = 0L,
                reportPath = engine.latestScanReportPath,
                failureReason = e.message,
                failure = e
            )
        }
    }

    private fun augmentReport(path: String, fallbackUsed: Boolean, failureReason: String?) {
        if (path.isBlank()) return
        try {
            val file = File(path)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.put(KEY_FALLBACK_USED, fallbackUsed)
            if (failureReason != null) {
                json.put("failureReason", failureReason)
            }
            val temporary = File(file.parentFile, ".${file.name}.worker.tmp")
            if (temporary.exists()) temporary.delete()
            temporary.writeText(json.toString(2))
            Os.rename(temporary.absolutePath, file.absolutePath)
        } catch (failure: Exception) {
            recordHandledWorkerFailure(applicationContext, "CompilationWorker", "Scan report update failed", failure)
        }
    }

    private fun encodeClipPlan(result: ScanTaskResult): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("classification", result.previewClassification.name)
        put("confirmedTransitionCount", result.confirmedTransitionCount)
        put("provisionalTransitionCount", result.provisionalTransitionCount)
        put("rejectedTransitionCount", result.rejectedTransitionCount)
        put("sourceDurationMs", result.durationMs)
        put("candidateTimestampsMs", JSONArray().apply {
            result.candidateTimestampsMs.forEach(::put)
        })
        put("transitionSummaries", transitionSummariesJson(result.transitionSummaries))
        put("segments", JSONArray().apply {
            result.segments.forEach { segment ->
                put(JSONObject().apply {
                    put("startMs", segment.startMs)
                    put("endMs", segment.endMs)
                    put("durationMs", (segment.endMs - segment.startMs).coerceAtLeast(0L))
                })
            }
        })
    }.toString()

    private fun parseScanWindow(raw: String?): ScanWindow {
        if (raw.isNullOrBlank()) {
            return ScanWindow(0.0f, 0.8f, 0.10f, 0.30f)
        }
        return runCatching {
            val json = JSONObject(raw)
            ScanWindow(
                (json.optDouble("xPercent", 0.0).toFloat()),
                (json.optDouble("yPercent", 0.8).toFloat()),
                (json.optDouble("widthPercent", 0.1).toFloat()),
                (json.optDouble("heightPercent", 0.3).toFloat())
            )
        }.getOrNull() ?: ScanWindow(0.0f, 0.8f, 0.10f, 0.30f)
    }

    private fun setProgressCompat(
        phase: String,
        message: String,
        percent: Int,
        fallbackUsed: Boolean = false,
        pipelineState: CompilationPipelineState = pipelineStateForProgressPhase(phase)
    ) {
        if (percent < 0 || percent > 100) return
        val safePercent = monotonicProgressPercent(
            CompilationJobStore(applicationContext).load()?.takeIf { it.workId == id.toString() }?.progressPercent ?: 0,
            percent
        )
        AppLog.i(applicationContext, "CompilationWorker", "[$phase] $message ($safePercent%) fallback=$fallbackUsed")
        CompilationJobStore(applicationContext).updateState(
            id.toString(),
            pipelineState,
            phase,
            message,
            safePercent
        )
        publishProgressData(phase, message, safePercent, fallbackUsed, pipelineState)
    }

    private fun publishProgressData(
        phase: String,
        message: String,
        percent: Int,
        fallbackUsed: Boolean = false,
        pipelineState: CompilationPipelineState = pipelineStateForProgressPhase(phase)
    ) {
        val data = workDataOf(
            KEY_PROGRESS_PHASE to phase,
            KEY_PROGRESS_MESSAGE to message,
            KEY_PROGRESS_PERCENT to percent,
            KEY_ELAPSED_MS to (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
            KEY_FALLBACK_USED to fallbackUsed,
            CompilationJobContract.KEY_PIPELINE_STATE to pipelineState.name
        )
        setProgressAsync(data)
    }

    private fun setForegroundCompat(
        phase: String,
        message: String,
        percent: Int
    ) {
        val safePercent = monotonicProgressPercent(
            CompilationJobStore(applicationContext).load()?.takeIf { it.workId == id.toString() }?.progressPercent ?: 0,
            percent
        )
        val notification = compileNotification(phase, message, safePercent)
        setForegroundAsync(createForegroundInfo(notification))
    }

    private fun progressPhaseForState(state: CompilationPipelineState, scanMode: ScanMode): String = when (state) {
        CompilationPipelineState.COARSE_SCAN -> if (scanMode == ScanMode.Experimental) "experimental scan" else "stable scan"
        CompilationPipelineState.REFINING -> "refining"
        CompilationPipelineState.FINALIZING -> "finalizing"
        CompilationPipelineState.BUILDING_CLIP_PLAN -> "clip plan"
        CompilationPipelineState.EXPORTING -> "export"
        CompilationPipelineState.VERIFYING -> "verify"
        CompilationPipelineState.NO_RESULTS -> "no_results"
        CompilationPipelineState.CANCELLED -> "cancelled"
        CompilationPipelineState.FAILED -> "failed"
        CompilationPipelineState.SUCCEEDED -> "completed"
        CompilationPipelineState.PROVISIONAL_SUCCEEDED -> "provisional_completed"
        CompilationPipelineState.QUEUED -> "queued"
        else -> "preparing"
    }

    private fun compileNotification(phase: String, message: String, percent: Int): android.app.Notification {
        ensureNotificationChannel()
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, COMPILATION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Compilation: $phase")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(intent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .build()
    }

    private fun createForegroundInfo(notification: android.app.Notification): ForegroundInfo =
        createCompilationForegroundInfo(notification)

    private fun promoteToForeground(phase: String, message: String, percent: Int): Result? {
        return try {
            val notification = compileNotification(phase, message, percent)
            val promoted = awaitForegroundPromotion(
                setForegroundAsync(createForegroundInfo(notification))
            ) { failure ->
                recordHandledWorkerFailure(
                    applicationContext,
                    "CompilationWorker",
                    "Foreground promotion failed",
                    failure
                )
            }
            if (promoted) null else foregroundPromotionFailureResult()
        } catch (cancelled: CancellationException) {
            propagateWorkerCancellation(cancelled)
        } catch (failure: RuntimeException) {
            recordHandledWorkerFailure(
                applicationContext,
                "CompilationWorker",
                "Foreground promotion failed",
                failure
            )
            foregroundPromotionFailureResult()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(COMPILATION_NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                COMPILATION_NOTIFICATION_CHANNEL_ID,
                "Compilation progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
        )
    }

    private data class ScanTaskResult(
        val success: Boolean,
        val segments: List<SegmentWindow>,
        val durationMs: Long,
        val reportPath: String?,
        val failureReason: String? = null,
        val failure: Throwable? = null,
        val candidateCount: Int = 0,
        val candidateTimestampsMs: List<Long> = emptyList(),
        val scanBudgetReached: Boolean = false,
        val visualFallbackUsed: Boolean = false,
        val confirmedTransitionCount: Int = 0,
        val provisionalTransitionCount: Int = 0,
        val rejectedTransitionCount: Int = 0,
        val completedCheckpointCount: Int = 0,
        val strategyFallbackUsed: Boolean = false,
        val strategyFallbackReason: String? = null,
        val recursiveProbeCount: Int = 0,
        val semanticLeafCount: Int = 0,
        val previewClassification: CompilationPreviewClassification = CompilationPreviewClassification.NONE,
        val transitionSummaries: List<ScanTransitionSummary> = emptyList()
    )

    companion object {
        const val KEY_SOURCE_URI = "sourceUri"
        const val KEY_SCAN_WINDOW = "scanWindow"
        const val KEY_SCAN_MODE = "scanMode"
        const val KEY_CHECKPOINT_INTERVAL_MS = "checkpointIntervalMs"
        const val KEY_SCANNER_PROFILE_ID = "scannerProfileId"
        const val KEY_EXPERIMENTAL_DOWNSCALE = "experimentalDownscale"
        const val KEY_QUALITY_ORDINAL = "qualityOrdinal"
        const val KEY_FORMAT_ORDINAL = "formatOrdinal"
        const val KEY_TRANSITION_STYLE_ORDINAL = "transitionStyleOrdinal"
        const val KEY_VIDEO_ROTATION = "videoRotation"
        const val KEY_PROGRESS_PHASE = "progressPhase"
        const val KEY_PROGRESS_MESSAGE = "progressMessage"
        const val KEY_PROGRESS_PERCENT = "progressPercent"
        const val KEY_ELAPSED_MS = "elapsedMs"
        const val KEY_OUTPUT_PATH = "outputPath"
        const val KEY_REPORT_PATH = "reportPath"
        const val KEY_FALLBACK_USED = "fallbackUsed"
        const val KEY_NO_TRANSITIONS_DETECTED = "noTransitionsDetected"
        const val KEY_ERROR_MESSAGE = "errorMessage"

        private const val COMPILATION_WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L
        private const val QUICK_MODE_PROFILE_ID = "QUICK_5_MIN"

    }
}

internal fun monotonicProgressPercent(previous: Int, requested: Int): Int =
    maxOf(previous.coerceIn(0, 100), requested.coerceIn(0, 100))
