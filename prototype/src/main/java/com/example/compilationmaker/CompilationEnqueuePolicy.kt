package com.example.compilationmaker

/** Minimal snapshot of work already registered under the compilation unique-work name. */
internal data class UniqueCompilationWorkSnapshot(
    val workId: String,
    val isActive: Boolean
)

internal enum class CompilationEnqueueAction {
    ENQUEUE_PROPOSED,
    ATTACH_EXISTING,
    REPLACE_STALE
}

/**
 * The id returned here is the id that must be persisted and observed. With KEEP, an already-active
 * request wins, so persisting the newly proposed id would track work that WorkManager never runs.
 */
internal data class CompilationEnqueueDecision(
    val action: CompilationEnqueueAction,
    val workIdToPersistAndObserve: String
)

internal fun decideUniqueCompilationEnqueue(
    proposedWorkId: String,
    persistedWorkId: String?,
    persistedWorkIsActive: Boolean,
    uniqueWork: List<UniqueCompilationWorkSnapshot>
): CompilationEnqueueDecision {
    require(proposedWorkId.isNotBlank()) { "Proposed work id must not be blank" }

    val activeWork = uniqueWork.filter { it.isActive && it.workId.isNotBlank() }
    val retained = activeWork.firstOrNull {
        persistedWorkIsActive && it.workId == persistedWorkId
    }

    return when {
        retained != null -> {
            CompilationEnqueueDecision(
                action = CompilationEnqueueAction.ATTACH_EXISTING,
                workIdToPersistAndObserve = retained.workId
            )
        }
        activeWork.isNotEmpty() -> {
            CompilationEnqueueDecision(
                action = CompilationEnqueueAction.REPLACE_STALE,
                workIdToPersistAndObserve = proposedWorkId
            )
        }
        else -> {
            CompilationEnqueueDecision(
                action = CompilationEnqueueAction.ENQUEUE_PROPOSED,
                workIdToPersistAndObserve = proposedWorkId
            )
        }
    }
}
