package com.example.compilationmaker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object UpdateScheduler {
    const val FOREGROUND_CHECK_INTERVAL_MS = 5L * 60L * 1000L
    const val BACKGROUND_CHECK_INTERVAL_MINUTES = 15L
    const val BACKGROUND_FLEX_MINUTES = 5L
    private const val UNIQUE_WORK_NAME = "compilationmaker-periodic-update-check"

    fun scheduleBackgroundChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            BACKGROUND_CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
            BACKGROUND_FLEX_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

internal class UpdateCheckWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val repository = UpdateRepository.get(applicationContext)
        return when (val check = repository.checkForUpdate()) {
            is UpdateCheckResult.UpToDate -> Result.success()
            is UpdateCheckResult.Failed -> if (check.retryable) Result.retry() else Result.success()
            is UpdateCheckResult.Available -> {
                val activeCompilation = CompilationJobStore(applicationContext)
                    .load()
                    ?.state
                    ?.isActive == true
                if (shouldAutomaticallyDownloadUpdate(repository.autoDownloadEnabled, activeCompilation)) {
                    when (val download = repository.downloadAndVerify(check.info)) {
                        is UpdateDownloadResult.Ready -> {
                            notifyOnce(repository, check.info, readyToInstall = true)
                            Result.success()
                        }
                        is UpdateDownloadResult.Failed -> {
                            notifyOnce(repository, check.info, readyToInstall = false)
                            if (download.retryable) Result.retry() else Result.success()
                        }
                    }
                } else {
                    notifyOnce(repository, check.info, readyToInstall = false)
                    Result.success()
                }
            }
        }
    }

    private fun notifyOnce(
        repository: UpdateRepository,
        info: UpdateInfo,
        readyToInstall: Boolean
    ) {
        if (!repository.shouldNotify(info, readyToInstall)) return
        if (UpdateNotifier.notify(applicationContext, info, readyToInstall)) {
            repository.markNotified(info, readyToInstall)
        }
    }
}

internal object UpdateNotifier {
    private const val CHANNEL_ID = "compilation_updates"
    private const val NOTIFICATION_ID = 6110

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Compilation Updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when a verified CompilationMaker update is available"
        }
        manager.createNotificationChannel(channel)
    }

    fun notify(context: Context, info: UpdateInfo, readyToInstall: Boolean): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        val action = if (readyToInstall) UpdateContract.ACTION_INSTALL else UpdateContract.ACTION_DOWNLOAD
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(UpdateContract.EXTRA_ACTION, action)
            putExtra(UpdateContract.EXTRA_VERSION_CODE, info.versionCode)
        }
        val requestCode = (info.versionCode xor if (readyToInstall) 0x5110 else 0x4110).toInt()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (readyToInstall) {
            "CompilationMaker update ready"
        } else {
            "CompilationMaker update available"
        }
        val message = if (readyToInstall) {
            "Version ${info.versionName} is verified. Tap to approve installation in Android."
        } else {
            "Version ${info.versionName} is available. Tap to download and verify it."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }
}
