package com.example.compilationmaker

import android.content.pm.ServiceInfo
import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class CompilationForegroundInfoTest {
    @Test
    fun api35AndHigherUseMediaProcessing() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            foregroundServiceTypeForSdk(35)
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            foregroundServiceTypeForSdk(36)
        )
    }

    @Test
    fun api29Through34UseDataSync() {
        for (sdk in 29..34) {
            assertEquals(
                "Unexpected service type for API $sdk",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                foregroundServiceTypeForSdk(sdk)
            )
        }
    }

    @Test
    fun noApi29OrHigherPolicyUsesTypeNone() {
        for (sdk in 29..40) {
            val serviceType = foregroundServiceTypeForSdk(sdk)
            assertNotNull("Missing service type for API $sdk", serviceType)
            assertTrue(
                "API $sdk selected FOREGROUND_SERVICE_TYPE_NONE",
                serviceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            )
        }
        assertNull(foregroundServiceTypeForSdk(28))
    }

    @Test
    fun progressUpdatesPreserveTheOriginalServiceType() {
        val initialType = foregroundServiceTypeForSdk(35)
        val updateTypes = listOf("starting", "scan", "export", "completed").map {
            foregroundServiceTypeForSdk(35)
        }

        assertTrue(updateTypes.all { it == initialType })
    }

    @Test
    fun foregroundAndActivityNotificationsHaveDifferentStableIds() {
        assertTrue(COMPILATION_NOTIFICATION_ID != ACTIVITY_PROGRESS_NOTIFICATION_ID)
    }

    @Test
    fun foregroundFailureIsRecordedAndReturnedWithoutThrowing() {
        val cause = IllegalStateException("bad foreground configuration")
        val future = CompletableFuture<Void>().apply { completeExceptionally(cause) }
        var recorded: Exception? = null

        val promoted = awaitForegroundPromotion(future) { recorded = it }

        assertFalse(promoted)
        assertNotNull(recorded)
        assertSame(cause, recorded?.cause)
    }

    @Test
    fun permanentPromotionFailureReturnsFailureWithoutRetry() {
        val result = foregroundPromotionFailureResult()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            FOREGROUND_PROMOTION_ERROR,
            result.outputData.getString(CompilationWorker.KEY_ERROR_MESSAGE)
        )
    }

    @Test
    fun interruptionIsRecordedAndRestoresTheInterruptFlag() {
        var recorded: Exception? = null
        try {
            val promoted = awaitForegroundPromotion(InterruptingFuture()) { recorded = it }

            assertFalse(promoted)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(recorded is InterruptedException)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun cancellationExitsWithoutBeingRecordedAsPromotionFailure() {
        val future = CompletableFuture<Void>().apply { cancel(true) }
        var recorded = false

        assertThrows(CancellationException::class.java) {
            awaitForegroundPromotion(future) { recorded = true }
        }
        assertFalse(recorded)
    }

    @Test
    fun workerCancellationIsPropagatedToWorkManager() {
        val cancellation = CancellationException("cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            propagateWorkerCancellation(cancellation)
        }
        assertSame(cancellation, thrown)
    }

    private class InterruptingFuture : Future<Any?> {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = true
        override fun get(): Any? = throw InterruptedException("interrupted")
        override fun get(timeout: Long, unit: TimeUnit): Any? = throw InterruptedException("interrupted")
    }
}
