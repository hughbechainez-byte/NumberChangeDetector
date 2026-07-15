package com.example.compilationmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompilationLifecycleSourceTest {
    private val sourceRoot = listOf(
        File("src/main/java/com/example/compilationmaker"),
        File("app/src/main/java/com/example/compilationmaker")
    ).first(File::isDirectory)

    @Test
    fun activityLifecycleNeverCancelsOrDeletesActiveWorkerOutputAndReplaceIsStaleOnly() {
        val source = sourceRoot.resolve("MainActivity.kt").readText()
        val onDestroy = source.substringAfter("override fun onDestroy()").substringBefore("private fun setUpUi")

        assertFalse(source.contains("cancelWorkById"))
        assertFalse(source.contains("cancelUniqueWork"))
        assertFalse(onDestroy.contains("delete()"))
        assertTrue(source.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(source.contains("replaceStaleWork"))
        assertTrue(source.contains("if (replaceStaleWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP"))
    }

    @Test
    fun roiInitializationIsGuardedBeforeAndAfterAsyncCapture() {
        val source = sourceRoot.resolve("MainActivity.kt").readText()

        assertTrue(source.contains("WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED"))
        assertTrue(source.contains("if (!canInitializeRoiUi())"))
        assertTrue(source.contains("if (!canInitializeRoiUi()) return@launch"))
        assertTrue(source.contains("frame?.recycle()"))
    }

    @Test
    fun unrelatedStatusChannelsCannotCallLegacyCompilationProgress() {
        val source = sourceRoot.resolve("MainActivity.kt").readText()

        assertFalse(source.contains("emitProgress("))
        assertTrue(source.contains("emitRoiStatus("))
        assertTrue(source.contains("emitUpdateStatus("))
        assertTrue(source.contains("emitLogStatus("))
        assertTrue(source.contains("emitTransientStatus("))
    }

    @Test
    fun postScanStagesAndTerminalReturnsRemainExplicitlyLogged() {
        val activity = sourceRoot.resolve("MainActivity.kt").readText()
        val worker = sourceRoot.resolve("CompilationWorker.kt").readText()
        listOf(
            "[finalize] candidate count=",
            "[finalize] candidate timestamps=",
            "[finalize] beginning candidate merge",
            "[finalize] number confirmation completed",
            "[finalize] confirmed transitions=",
            "[clip plan] generating clips",
            "[clip plan] clip start/end timestamps=",
            "[export] beginning",
            "[export] destination URI/path=",
            "[verify] output exists=",
            "[verify] output duration="
        ).forEach { required -> assertTrue("Missing log: $required", activity.contains(required)) }
        assertTrue(worker.contains("[worker] returning success"))
        assertTrue(worker.contains("[worker] returning failure"))
        assertTrue(worker.contains("[worker] returning cancelled"))
    }

    @Test
    fun pictureInPictureUsesAutoEnterAndACompactStatusSurface() {
        val activity = sourceRoot.resolve("MainActivity.kt").readText()
        val layout = listOf(
            File("src/main/res/layout/activity_main.xml"),
            File("app/src/main/res/layout/activity_main.xml")
        ).first(File::isFile).readText()

        assertTrue(activity.contains("setAutoEnterEnabled(activeJob)"))
        assertTrue(activity.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.S"))
        assertTrue(activity.contains("override fun onPictureInPictureModeChanged"))
        assertTrue(activity.contains("@Volatile"))
        assertTrue(activity.contains("private var compilationWorkId"))
        assertTrue(layout.contains("@+id/pipStatusContainer"))
        assertTrue(layout.contains("@+id/pipPercentText"))
        assertTrue(layout.contains("@+id/pipStatusText"))
        assertTrue(layout.contains("@+id/coreStatusSwitch"))
    }

    @Test
    fun coreTelemetryIsProcessLocalAndForegroundWakeLockIsBounded() {
        val telemetry = sourceRoot.resolve("CoreActivityTelemetry.kt").readText()
        val worker = sourceRoot.resolve("CompilationWorker.kt").readText()
        val foregroundUpdate = worker.substringAfter("private fun setForegroundCompat(")
            .substringBefore("private fun progressPhaseForState")

        assertFalse(telemetry.contains("WorkManager."))
        assertFalse(telemetry.contains("getSharedPreferences("))
        assertFalse(telemetry.contains("AppLog."))
        assertFalse(foregroundUpdate.contains("publishProgressData("))
        assertTrue(worker.contains("acquire(COMPILATION_WAKE_LOCK_TIMEOUT_MS)"))
        assertTrue(worker.contains("wakeLock?.takeIf { it.isHeld }"))
        assertTrue(worker.contains("heldWakeLock.release()"))
    }
}
