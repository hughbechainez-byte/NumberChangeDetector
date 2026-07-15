package com.example.compilationmaker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CRASH_LOG_FILE = "last-crash.log"
private const val TRACE_LOG_FILE = "app-trace.log"
private const val TRACE_LOG_MAX_BYTES = 256 * 1024
private const val TRACE_TAIL_BYTES = 160 * 1024

private val crashLogLock = Any()
@Volatile private var crashRecorderInstalled = false

data class SavedLog(
    val title: String,
    val text: String,
    val isCrash: Boolean
)

object AppLog {
    fun d(context: Context, tag: String, message: String) = record(context, Log.DEBUG, tag, message, null)
    fun i(context: Context, tag: String, message: String) = record(context, Log.INFO, tag, message, null)
    fun w(context: Context, tag: String, message: String, throwable: Throwable? = null) =
        record(context, Log.WARN, tag, message, throwable)

    fun e(context: Context, tag: String, message: String, throwable: Throwable? = null) =
        record(context, Log.ERROR, tag, message, throwable)
}

fun installCrashRecorder(context: Context) {
    val appContext = context.applicationContext
    synchronized(crashLogLock) {
        if (crashRecorderInstalled) return
        crashRecorderInstalled = true
    }

    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            record(
                appContext,
                Log.ERROR,
                "CrashLogStore",
                "Unhandled exception on ${thread.name}: ${throwable::class.java.simpleName}: ${throwable.message ?: ""}",
                throwable
            )
            writeCrashReport(appContext, thread.name, throwable)
        }
        previous?.uncaughtException(thread, throwable)
    }

    AppLog.i(appContext, "CrashLogStore", "Session started version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
}

fun writeCrashReport(context: Context, threadName: String, throwable: Throwable) {
    writeExceptionReport(context, "crash", threadName, throwable)
}

fun recordHandledWorkerFailure(context: Context, workerName: String, message: String, throwable: Throwable) {
    val appContext = context.applicationContext
    AppLog.e(appContext, workerName, message, throwable)
    runCatching {
        writeExceptionReport(appContext, "worker-failure", Thread.currentThread().name, throwable)
    }.onFailure { loggingFailure ->
        AppLog.e(appContext, "CrashLogStore", "Unable to persist handled worker failure", loggingFailure)
    }
}

private fun writeExceptionReport(context: Context, kind: String, threadName: String, throwable: Throwable) {
    val appContext = context.applicationContext
    persistExceptionReport(
        crashFile = File(appContext.filesDir, CRASH_LOG_FILE),
        traceFile = File(appContext.filesDir, TRACE_LOG_FILE),
        kind = kind,
        threadName = threadName,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        throwable = throwable,
        reportTimestamp = timestamp()
    )
}

internal fun persistExceptionReport(
    crashFile: File,
    traceFile: File,
    kind: String,
    threadName: String,
    versionName: String,
    versionCode: Int,
    throwable: Throwable,
    reportTimestamp: String
) {
    val traceTail = readTail(traceFile, TRACE_TAIL_BYTES)
    val writer = StringWriter()
    throwable.printStackTrace(PrintWriter(writer))
    crashFile.parentFile?.mkdirs()
    crashFile.writeText(
        buildString {
            appendLine("kind=$kind")
            appendLine("timestamp=$reportTimestamp")
            appendLine("thread=$threadName")
            appendLine("version=$versionName ($versionCode)")
            appendLine("message=${throwable.message ?: ""}")
            if (traceTail.isNotBlank()) {
                appendLine()
                appendLine("recent-trace:")
                appendLine(traceTail.trimEnd())
            }
            appendLine()
            appendLine("stacktrace:")
            append(writer.toString().trimEnd())
            appendLine()
        }
    )
}

fun readCrashReport(context: Context): String? {
    val file = File(context.applicationContext.filesDir, CRASH_LOG_FILE)
    return if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
}

fun readSavedLog(context: Context): SavedLog? {
    val appContext = context.applicationContext
    val crash = File(appContext.filesDir, CRASH_LOG_FILE)
    if (crash.exists()) {
        crash.readText().takeIf { it.isNotBlank() }?.let {
            return SavedLog("Crash log", it, true)
        }
    }

    val trace = File(appContext.filesDir, TRACE_LOG_FILE)
    if (trace.exists()) {
        trace.readText().takeIf { it.isNotBlank() }?.let {
            return SavedLog("Recent app log", it, false)
        }
    }

    return null
}

fun clearCrashReport(context: Context) {
    val appContext = context.applicationContext
    synchronized(crashLogLock) {
        File(appContext.filesDir, CRASH_LOG_FILE).delete()
        File(appContext.filesDir, TRACE_LOG_FILE).delete()
    }
}

private fun record(context: Context, level: Int, tag: String, message: String, throwable: Throwable? = null) {
    val appContext = context.applicationContext
    val line = buildString {
        append('[').append(timestamp()).append("] ")
        append(levelLabel(level)).append('/').append(tag).append(": ").append(message)
        if (throwable != null) {
            appendLine()
            appendLine(stackTraceString(throwable).trimEnd())
        }
    }

    synchronized(crashLogLock) {
        runCatching {
            appendTraceLine(appContext, line)
        }
    }

    when (level) {
        Log.VERBOSE -> Log.v(tag, message, throwable)
        Log.DEBUG -> Log.d(tag, message, throwable)
        Log.INFO -> Log.i(tag, message, throwable)
        Log.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        Log.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        else -> Log.i(tag, message, throwable)
    }
}

private fun appendTraceLine(context: Context, line: String) {
    val file = File(context.filesDir, TRACE_LOG_FILE)
    file.parentFile?.mkdirs()
    if (file.exists() && file.length() > TRACE_LOG_MAX_BYTES) {
        val trimmed = readTail(file, TRACE_TAIL_BYTES)
        file.writeText(trimmed)
    }
    if (file.exists() && file.length() > 0L) {
        file.appendText(System.lineSeparator())
    }
    file.appendText(line)
}

private fun readTail(file: File, maxBytes: Int): String {
    if (!file.exists() || file.length() <= 0L) return ""
    val length = file.length()
    val start = maxOf(0L, length - maxBytes)
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(start)
        val bytes = ByteArray((length - start).toInt())
        raf.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        return if (start > 0L) text.substringAfter('\n', text) else text
    }
}

private fun timestamp(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}

private fun levelLabel(level: Int): String = when (level) {
    Log.VERBOSE -> "V"
    Log.DEBUG -> "D"
    Log.INFO -> "I"
    Log.WARN -> "W"
    Log.ERROR -> "E"
    else -> "I"
}

private fun stackTraceString(throwable: Throwable): String {
    val writer = StringWriter()
    throwable.printStackTrace(PrintWriter(writer))
    return writer.toString()
}
