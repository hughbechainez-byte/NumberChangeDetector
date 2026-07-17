package com.example.compilationmaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Runs the existing single-video worker serially so phone hardware is never oversubscribed. */
class BatchCompilationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val items = parseItems(inputData.getString(KEY_ITEMS_JSON).orEmpty())
        if (items.isEmpty()) return@withContext Result.failure(workDataOf(KEY_ERROR to "No videos were selected"))
        val batchId = inputData.getString(KEY_BATCH_ID).orEmpty().ifBlank { id.toString() }
        val format = ExportFormat.values().getOrNull(inputData.getInt(CompilationWorker.KEY_FORMAT_ORDINAL, 0)) ?: ExportFormat.Mp4
        val startedAt = System.currentTimeMillis()
        val store = CompilationBatchStore(applicationContext)
        store.save(CompilationBatchRecord(batchId, id.toString(), startedAt, startedAt, items))
        setForeground(makeForeground("Batch queued", 0))
        try {
            items.forEachIndexed { index, item ->
                ensureActive()
                val itemStarted = System.currentTimeMillis()
                store.update { record -> record.withItem(index) { it.copy(state = "running", elapsedMs = 0L) } }
                val output = createOutputFile(batchId, index, format)
                val childData = androidx.work.Data.Builder()
                    .putString(CompilationWorker.KEY_SOURCE_URI, item.uri)
                    .putString(CompilationJobContract.KEY_EXPECTED_OUTPUT_PATH, output.absolutePath)
                    .putString(CompilationWorker.KEY_SCAN_WINDOW, inputData.getString(CompilationWorker.KEY_SCAN_WINDOW))
                    .putInt(CompilationWorker.KEY_SCAN_MODE, inputData.getInt(CompilationWorker.KEY_SCAN_MODE, ScanMode.StableCheckpoint.ordinal))
                    .putLong(CompilationWorker.KEY_CHECKPOINT_INTERVAL_MS, inputData.getLong(CompilationWorker.KEY_CHECKPOINT_INTERVAL_MS, 30_000L))
                    .putString(CompilationWorker.KEY_SCANNER_PROFILE_ID, inputData.getString(CompilationWorker.KEY_SCANNER_PROFILE_ID))
                    .putInt(CompilationWorker.KEY_EXPERIMENTAL_DOWNSCALE, inputData.getInt(CompilationWorker.KEY_EXPERIMENTAL_DOWNSCALE, 32))
                    .putInt(CompilationWorker.KEY_QUALITY_ORDINAL, inputData.getInt(CompilationWorker.KEY_QUALITY_ORDINAL, ExportQuality.Medium.ordinal))
                    .putInt(CompilationWorker.KEY_FORMAT_ORDINAL, inputData.getInt(CompilationWorker.KEY_FORMAT_ORDINAL, ExportFormat.Mp4.ordinal))
                    .putInt(CompilationWorker.KEY_TRANSITION_STYLE_ORDINAL, inputData.getInt(CompilationWorker.KEY_TRANSITION_STYLE_ORDINAL, TransitionStyle.Instant.ordinal))
                    .putInt(CompilationWorker.KEY_VIDEO_ROTATION, inputData.getInt(CompilationWorker.KEY_VIDEO_ROTATION, 0))
                    .build()
                val child = OneTimeWorkRequestBuilder<CompilationWorker>().setInputData(childData).build()
                WorkManager.getInstance(applicationContext).enqueue(child).result.get()
                var info: WorkInfo?
                do {
                    ensureActive()
                    info = WorkManager.getInstance(applicationContext).getWorkInfoById(child.id).get()
                    val childPercent = info?.progress?.getInt(CompilationWorker.KEY_PROGRESS_PERCENT, 0) ?: 0
                    val overall = ((index * 100f + childPercent) / items.size).roundToInt().coerceIn(0, 99)
                    val elapsed = System.currentTimeMillis() - startedAt
                    setProgress(workDataOf(
                        KEY_INDEX to index,
                        KEY_TOTAL to items.size,
                        KEY_PERCENT to overall,
                        KEY_ELAPSED_MS to elapsed,
                        KEY_STATUS to "${item.label} (${index + 1}/${items.size})"
                    ))
                    setForeground(makeForeground("Video ${index + 1}/${items.size} • ${formatElapsed(elapsed)}", overall))
                    if (info?.state?.isFinished != true) delay(500L)
                } while (info?.state?.isFinished != true)
                val elapsed = System.currentTimeMillis() - itemStarted
                val outputPath = info?.outputData?.getString(CompilationWorker.KEY_OUTPUT_PATH).orEmpty().ifBlank { output.absolutePath.takeIf { File(it).exists() }.orEmpty() }
                val failed = info?.state != WorkInfo.State.SUCCEEDED
                val error = info?.outputData?.getString(CompilationWorker.KEY_ERROR_MESSAGE).orEmpty()
                store.update { record -> record.withItem(index) { it.copy(state = if (failed) "failed" else "completed", elapsedMs = elapsed, outputPath = outputPath, error = error) } }
            }
            val elapsed = System.currentTimeMillis() - startedAt
            setProgress(workDataOf(KEY_INDEX to items.size, KEY_TOTAL to items.size, KEY_PERCENT to 100, KEY_ELAPSED_MS to elapsed, KEY_STATUS to "Batch complete"))
            setForeground(makeForeground("Batch complete • ${formatElapsed(elapsed)}", 100))
            Result.success(workDataOf(KEY_ELAPSED_MS to elapsed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            setProgress(workDataOf(KEY_PERCENT to 100, KEY_ELAPSED_MS to elapsed, KEY_STATUS to "Batch stopped: ${failure.message.orEmpty()}"))
            Result.failure(workDataOf(KEY_ERROR to (failure.message ?: failure::class.java.simpleName), KEY_ELAPSED_MS to elapsed))
        }
    }

    private fun createOutputFile(batchId: String, index: Int, format: ExportFormat): File {
        val dir = File(applicationContext.getExternalFilesDir("movies"), "CompilationMaker").apply { mkdirs() }
        return File(dir, "batch_${batchId.take(12)}_${index + 1}.${format.extension}")
    }

    private fun makeForeground(message: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Compilation batches", NotificationManager.IMPORTANCE_LOW))
        }
        val intent = PendingIntent.getActivity(applicationContext, 42, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("CompilationMaker batch")
            .setContentText(message)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
        return createCompilationForegroundInfo(notification)
    }

    private fun parseItems(raw: String): List<CompilationBatchItem> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            CompilationBatchItem(json.optString("uri"), json.optString("label"))
        }
    }

    private suspend fun ensureActive() { currentCoroutineContext().ensureActive() }

    companion object {
        const val KEY_ITEMS_JSON = "batchItemsJson"
        const val KEY_BATCH_ID = "batchId"
        const val KEY_INDEX = "batchIndex"
        const val KEY_TOTAL = "batchTotal"
        const val KEY_PERCENT = "batchPercent"
        const val KEY_ELAPSED_MS = "batchElapsedMs"
        const val KEY_STATUS = "batchStatus"
        const val KEY_ERROR = "batchError"
        private const val CHANNEL = "compilation_batch"
        private const val NOTIFICATION_ID = 7104
    }
}

private fun CompilationBatchRecord.withItem(index: Int, transform: (CompilationBatchItem) -> CompilationBatchItem): CompilationBatchRecord =
    copy(items = items.mapIndexed { itemIndex, item -> if (itemIndex == index) transform(item) else item })

private fun formatElapsed(ms: Long): String = "%02d:%02d".format(ms / 60_000L, (ms / 1_000L) % 60L)
