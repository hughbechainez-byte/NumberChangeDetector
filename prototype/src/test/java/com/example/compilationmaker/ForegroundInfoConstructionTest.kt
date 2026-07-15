package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForegroundInfoConstructionTest {
    @Test
    fun allForegroundInfoConstructorsAreCentralizedAndUpdatesUseTheFactory() {
        val sourceRoot = locateSourceRoot()
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val constructorPattern = Regex("(?<![A-Za-z0-9_])ForegroundInfo\\(")
        val constructorFiles = kotlinFiles.filter { constructorPattern.containsMatchIn(it.readText()) }

        assertEquals(listOf("CompilationForegroundInfo.kt"), constructorFiles.map(File::getName).distinct())

        val workerSource = sourceRoot.resolve("CompilationWorker.kt").readText()
        assertEquals(
            2,
            Regex("setForegroundAsync\\(createForegroundInfo\\(notification\\)\\)").findAll(workerSource).count()
        )
    }

    @Test
    fun activityCannotReplaceTheWorkManagerForegroundNotification() {
        val activitySource = locateSourceRoot().resolve("MainActivity.kt").readText()

        assertTrue(activitySource.contains("progressNotificationId = ACTIVITY_PROGRESS_NOTIFICATION_ID"))
        assertFalse(activitySource.contains("progressNotificationId = COMPILATION_NOTIFICATION_ID"))
        assertTrue(activitySource.contains("if (compilationWorkId != null) return"))
    }

    private fun locateSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/example/compilationmaker"),
            File("app/src/main/java/com/example/compilationmaker")
        )
        return requireNotNull(candidates.firstOrNull(File::isDirectory)) { "Main source directory not found" }
    }
}
