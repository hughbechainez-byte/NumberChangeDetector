package com.example.compilationmaker

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

internal const val COMPILATION_NOTIFICATION_CHANNEL_ID = "compilation_progress"
internal const val COMPILATION_NOTIFICATION_ID = 6106
internal const val ACTIVITY_PROGRESS_NOTIFICATION_ID = 6107
internal const val FOREGROUND_PROMOTION_ERROR = "Unable to start required foreground processing"

internal fun foregroundServiceTypeForSdk(sdkInt: Int): Int? = when {
    sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    sdkInt >= Build.VERSION_CODES.Q ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    else -> null
}

internal fun createCompilationForegroundInfo(
    notification: Notification,
    sdkInt: Int = Build.VERSION.SDK_INT
): ForegroundInfo {
    val serviceType = foregroundServiceTypeForSdk(sdkInt)
    if (serviceType == null) {
        return ForegroundInfo(COMPILATION_NOTIFICATION_ID, notification)
    }

    check(serviceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
        "API $sdkInt requires an explicit foreground service type"
    }
    return ForegroundInfo(COMPILATION_NOTIFICATION_ID, notification, serviceType)
}

internal fun awaitForegroundPromotion(
    future: Future<*>,
    recordFailure: (Exception) -> Unit
): Boolean {
    return try {
        future.get()
        true
    } catch (failure: ExecutionException) {
        recordFailure(failure)
        false
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        recordFailure(interrupted)
        false
    }
}

internal fun foregroundPromotionFailureResult(): ListenableWorker.Result =
    ListenableWorker.Result.failure(
        workDataOf(CompilationWorker.KEY_ERROR_MESSAGE to FOREGROUND_PROMOTION_ERROR)
    )

internal fun propagateWorkerCancellation(cancelled: CancellationException): Nothing = throw cancelled
