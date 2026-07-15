package com.example.compilationmaker

import com.hughbechainez.numberchangedetector.scanner.CoreActivityEvent
import java.util.concurrent.CopyOnWriteArraySet

internal data class CompilationCoreActivity(
    val workId: String,
    val event: CoreActivityEvent
)

/**
 * Process-local diagnostic stream. It deliberately avoids WorkManager progress, AppLog, and
 * SharedPreferences so live diagnostics cannot add database or disk I/O to the scan hot path.
 */
internal object CoreActivityTelemetry {
    private val listeners = CopyOnWriteArraySet<(CompilationCoreActivity) -> Unit>()

    fun addListener(listener: (CompilationCoreActivity) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (CompilationCoreActivity) -> Unit) {
        listeners -= listener
    }

    fun emit(workId: String, event: CoreActivityEvent) {
        val activity = CompilationCoreActivity(workId, event)
        listeners.forEach { listener ->
            runCatching { listener(activity) }
        }
    }
}
