package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Test

class CompilationEnqueuePolicyTest {
    @Test
    fun orphanedActiveWorkIsReplacedInsteadOfSuppressingExplicitNewRequest() {
        val decision = decideUniqueCompilationEnqueue(
            proposedWorkId = "new-request-that-will-not-run",
            persistedWorkId = null,
            persistedWorkIsActive = false,
            uniqueWork = listOf(
                UniqueCompilationWorkSnapshot("actual-running-request", isActive = true)
            )
        )

        assertEquals(CompilationEnqueueAction.REPLACE_STALE, decision.action)
        assertEquals("new-request-that-will-not-run", decision.workIdToPersistAndObserve)
    }

    @Test
    fun persistedActiveIdWinsIfUniqueWorkHistoryContainsSeveralEntries() {
        val decision = decideUniqueCompilationEnqueue(
            proposedWorkId = "proposed",
            persistedWorkId = "persisted-active",
            persistedWorkIsActive = true,
            uniqueWork = listOf(
                UniqueCompilationWorkSnapshot("other-active", isActive = true),
                UniqueCompilationWorkSnapshot("persisted-active", isActive = true)
            )
        )

        assertEquals(CompilationEnqueueAction.ATTACH_EXISTING, decision.action)
        assertEquals("persisted-active", decision.workIdToPersistAndObserve)
    }

    @Test
    fun terminalHistoryAllowsARealNewRequest() {
        val decision = decideUniqueCompilationEnqueue(
            proposedWorkId = "new-request",
            persistedWorkId = "old-request",
            persistedWorkIsActive = false,
            uniqueWork = listOf(
                UniqueCompilationWorkSnapshot("old-request", isActive = false)
            )
        )

        assertEquals(CompilationEnqueueAction.ENQUEUE_PROPOSED, decision.action)
        assertEquals("new-request", decision.workIdToPersistAndObserve)
    }
}
