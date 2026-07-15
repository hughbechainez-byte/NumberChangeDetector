package com.example.compilationmaker

internal enum class BackgroundStatusMode(val label: String) {
    CONCISE("Concise heartbeat (30 seconds)"),
    CORE_ACTIVITY("Core activity log (live)")
}

internal data class BackgroundStatusSnapshot(
    val phase: String,
    val pipelineState: CompilationPipelineState,
    val workManagerState: String,
    val message: String,
    val percent: Int,
    val observedAtMs: Long
) {
    val safePercent: Int = percent.coerceIn(0, 100)
}

internal fun shouldAppendBackgroundStatus(
    mode: BackgroundStatusMode,
    previous: BackgroundStatusSnapshot?,
    current: BackgroundStatusSnapshot,
    lastFeedAtMs: Long,
    conciseHeartbeatMs: Long = 30_000L
): Boolean {
    if (previous == null || mode == BackgroundStatusMode.CORE_ACTIVITY) return true
    if (current.pipelineState != previous.pipelineState || current.phase != previous.phase) return true
    if (current.safePercent >= 100 || current.pipelineState.isTerminal) return true
    return current.observedAtMs - lastFeedAtMs >= conciseHeartbeatMs.coerceAtLeast(1L)
}

internal fun formatBackgroundStatus(snapshot: BackgroundStatusSnapshot, coreStyle: Boolean): String {
    val phase = snapshot.phase.ifBlank { snapshot.pipelineState.name.lowercase() }
    return if (coreStyle) {
        "${snapshot.safePercent}% | worker=${snapshot.workManagerState} | pipeline=${snapshot.pipelineState.name} | phase=$phase | ${snapshot.message}"
    } else {
        "${snapshot.safePercent}% | $phase | ${snapshot.message}"
    }
}
