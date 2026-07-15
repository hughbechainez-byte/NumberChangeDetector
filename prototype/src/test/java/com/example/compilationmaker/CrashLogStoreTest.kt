package com.example.compilationmaker

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ExecutionException

class CrashLogStoreTest {
    @Test
    fun handledWorkerFailurePersistsTheCompleteExceptionWithoutThrowing() {
        val directory = Files.createTempDirectory("compilation-worker-failure").toFile()
        try {
            val crashFile = directory.resolve("last-crash.log")
            val traceFile = directory.resolve("app-trace.log").apply {
                writeText("worker reached foreground promotion")
            }
            val cause = IllegalStateException("invalid foreground service type")
            val failure = ExecutionException("promotion failed", cause)

            persistExceptionReport(
                crashFile = crashFile,
                traceFile = traceFile,
                kind = "worker-failure",
                threadName = "test-worker",
                versionName = "test",
                versionCode = 1,
                throwable = failure,
                reportTimestamp = "2026-07-11 00:00:00.000"
            )

            val report = crashFile.readText()
            assertTrue(report.contains("kind=worker-failure"))
            assertTrue(report.contains("worker reached foreground promotion"))
            assertTrue(report.contains(ExecutionException::class.java.name))
            assertTrue(report.contains(IllegalStateException::class.java.name))
            assertTrue(report.contains("invalid foreground service type"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
