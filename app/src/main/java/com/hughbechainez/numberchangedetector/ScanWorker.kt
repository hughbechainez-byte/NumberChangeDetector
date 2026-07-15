package com.hughbechainez.numberchangedetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hughbechainez.numberchangedetector.scanner.CornerNumberTransitionDetector
import com.hughbechainez.numberchangedetector.scanner.CornerPreset
import com.hughbechainez.numberchangedetector.scanner.ScanProfile
import com.hughbechainez.numberchangedetector.scanner.TransitionDetectionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = inputData.getString(KEY_SOURCE_URI)?.let(Uri::parse)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing source video"))
        val corner = inputData.getString(KEY_CORNER)?.let { runCatching { CornerPreset.valueOf(it) }.getOrNull() }
            ?: CornerPreset.BOTTOM_LEFT
        val profile = inputData.getString(KEY_PROFILE)?.let { runCatching { ScanProfile.valueOf(it) }.getOrNull() }
            ?: ScanProfile.FAST

        return@withContext try {
            setForeground(createForegroundInfo("Preparing scan", 0))
            val detector = CornerNumberTransitionDetector(applicationContext)
            val result = detector.detect(
                TransitionDetectionRequest(uri, corner.window, profile)
            ) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_PHASE to progress.phase,
                        KEY_PROGRESS_MESSAGE to progress.message,
                        KEY_PROGRESS_PERCENT to progress.percent
                    )
                )
            }
            val directory = File(applicationContext.filesDir, "scan-results").apply { mkdirs() }
            val output = File(directory, "number-transitions-${id}.json")
            output.writeText(resultToJson(result), Charsets.UTF_8)
            Result.success(
                workDataOf(
                    KEY_RESULT_PATH to output.absolutePath,
                    KEY_TRANSITION_COUNT to result.transitions.size
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (failure.message ?: failure::class.java.simpleName)))
        }
    }

    private fun createForegroundInfo(message: String, percent: Int): ForegroundInfo {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Finding number changes")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            ForegroundInfo(NOTIFICATION_ID, notification, type)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Number scan progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val KEY_SOURCE_URI = "sourceUri"
        const val KEY_CORNER = "corner"
        const val KEY_PROFILE = "profile"
        const val KEY_PROGRESS_PHASE = "progressPhase"
        const val KEY_PROGRESS_MESSAGE = "progressMessage"
        const val KEY_PROGRESS_PERCENT = "progressPercent"
        const val KEY_RESULT_PATH = "resultPath"
        const val KEY_TRANSITION_COUNT = "transitionCount"
        const val KEY_ERROR = "error"
        const val UNIQUE_WORK_NAME = "corner_number_timestamp_scan"
        private const val CHANNEL_ID = "number-scan-progress"
        private const val NOTIFICATION_ID = 4101
    }
}
