package com.example.compilationmaker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OcrConfirmationPolicyTest {
    @Test
    fun elevenCandidatesReceiveMoreThanLegacyFixedBudgetButRemainCapped() {
        assertTrue(ConfirmationTimeoutPolicy.overallMs(11) > 35_000L)
        assertEquals(
            ConfirmationTimeoutPolicy.OVERALL_CAP_MS,
            ConfirmationTimeoutPolicy.overallMs(10_000)
        )
    }

    @Test
    fun candidateDeadlineScalesWithBoundedOcrWorkInsteadOfForegroundSpeed() {
        val generic = ConfirmationTimeoutPolicy.candidateMs(
            ConfirmationTimeoutPolicy.GENERIC_CANDIDATE_WORK_UNITS
        )
        val checkpoint = ConfirmationTimeoutPolicy.candidateMs(
            ConfirmationTimeoutPolicy.CHECKPOINT_BOUNDARY_WORK_UNITS
        )

        assertTrue(checkpoint > 16_000L)
        assertTrue(checkpoint > generic)
        assertEquals(
            ConfirmationTimeoutPolicy.CANDIDATE_CAP_MS,
            ConfirmationTimeoutPolicy.candidateMs(Int.MAX_VALUE)
        )
    }

    @Test
    fun thirtyNineSecondCandidateIsNotMisreportedAsExpiredAfterThreeSeconds() {
        var nowMs = 10_000L
        val tracker = ConfirmationDeadlineTracker(
            globalStartMs = 10_000L,
            globalBudgetMs = 120_000L,
            localStartMs = 10_000L,
            localBudgetMs = 39_000L,
            clock = MonotonicTimeSource { nowMs }
        )

        nowMs += 3_000L
        val innerTimeoutSnapshot = tracker.snapshot()
        assertFalse(innerTimeoutSnapshot.localExpired)
        assertEquals(36_000L, innerTimeoutSnapshot.localDeadlineMs - nowMs)
        assertEquals("nested_timeout_propagated", propagatedConfirmationTimeoutSource(innerTimeoutSnapshot))

        nowMs = innerTimeoutSnapshot.localDeadlineMs
        val candidateTimeoutSnapshot = tracker.snapshot()
        assertTrue(candidateTimeoutSnapshot.localExpired)
        assertEquals("candidate_local", propagatedConfirmationTimeoutSource(candidateTimeoutSnapshot))
    }

    @Test
    fun confirmationBudgetsUseMonotonicRemainingTimeAndFreshCandidateStarts() {
        var nowMs = 1_000L
        val first = ConfirmationDeadlineTracker(
            globalStartMs = 1_000L,
            globalBudgetMs = 100_000L,
            localStartMs = 1_000L,
            localBudgetMs = 39_000L,
            clock = MonotonicTimeSource { nowMs }
        )
        val remaining = mutableListOf<Long>()
        listOf(1_000L, 4_000L, 15_000L, 39_999L).forEach { sample ->
            nowMs = sample
            remaining += first.snapshot().remainingGlobalMs
        }
        assertTrue(remaining.zipWithNext().all { (before, after) -> after <= before })

        nowMs = 45_000L
        val second = ConfirmationDeadlineTracker(
            globalStartMs = 1_000L,
            globalBudgetMs = 100_000L,
            localStartMs = nowMs,
            localBudgetMs = 39_000L,
            clock = MonotonicTimeSource { nowMs }
        )
        nowMs += 3_000L
        assertEquals(3_000L, second.snapshot().elapsedLocalMs)
        assertFalse(second.snapshot().localExpired)
    }

    @Test
    fun laterCandidateTimeoutCannotEraseEarlierConfirmations() {
        val ledger = IncrementalTransitionLedger(dedupeMs = 900L)
        ledger.confirm(10_000L)
        ledger.confirm(40_000L)
        // Candidate three times out and therefore never calls confirm.
        assertEquals(listOf(10_000L, 40_000L), ledger.snapshot())
    }

    @Test
    fun smallRoiIsUpscaledWithoutChangingAspectRatio() {
        val (width, height) = OcrPreparationPolicy.targetSize(32, 48)
        assertTrue(width >= OcrPreparationPolicy.MIN_RECOGNITION_SIDE_PX)
        assertTrue(height >= OcrPreparationPolicy.MIN_RECOGNITION_SIDE_PX)
        assertEquals(32f / 48f, width.toFloat() / height.toFloat(), 0.01f)
    }

    @Test
    fun emptyAndMalformedTextHaveDistinctNormalResults() {
        assertEquals(DigitRecognitionStatus.NO_TEXT, DigitRecognition(null, "", 0f, "raw").status)
        assertEquals(DigitRecognitionStatus.NO_VALID_INTEGER, DigitRecognition(null, "score", 0f, "raw").status)
    }

    @Test
    fun preprocessingStopsAfterValidResultOrTimeout() {
        assertFalse(shouldTryNextOcrVariant(0, DigitRecognition(7, "7", 0.95f, "raw")))
        assertFalse(shouldTryNextOcrVariant(0, DigitRecognition(null, "", 0f, "raw", DigitRecognitionStatus.TIMEOUT)))
        assertTrue(shouldTryNextOcrVariant(0, DigitRecognition(null, "", 0f, "raw")))
    }

    @Test
    fun timedOutAttemptIsTerminalForThatSampleInsteadOfRetryingInCancelledContext() {
        val timeout = DigitRecognition(null, "", 0f, "raw", DigitRecognitionStatus.TIMEOUT)
        assertFalse(shouldTryNextOcrVariant(0, timeout))
    }

    @Test
    fun visionKitErrorThirteenRemainsCandidateLocal() {
        assertTrue(mlKitFailureIsCandidateLocal(13))
    }

    @Test
    fun visualFallbackUsesOnlyStrongVisualChangesAndDeduplicatesNeighbors() {
        val selected = selectVisualFallbackTransitions(
            listOf(
                VisualFallbackCandidate(20_000L, 15f, true),
                VisualFallbackCandidate(20_400L, 18f, true),
                VisualFallbackCandidate(60_000L, 4f, true),
                VisualFallbackCandidate(90_000L, 20f, false)
            ),
            visualThreshold = 10f,
            dedupeMs = 900L
        )
        assertEquals(listOf(20_000L), selected)
    }

    @Test
    fun fallbackClipBoundariesRemainClampedToTenBeforeAndThirtyAfter() {
        assertEquals(SegmentWindow(0L, 35_000L), buildRequestedSegment(5_000L, 100_000L))
        assertEquals(SegmentWindow(85_000L, 100_000L), buildRequestedSegment(95_000L, 100_000L))
    }

    @Test
    fun totalJobProgressNeverMovesBackwardAcrossPhases() {
        assertEquals(100, monotonicProgressPercent(100, 44))
        assertEquals(55, monotonicProgressPercent(44, 55))
    }

    @Test
    fun fiveSampleTimelineRequiresMajorityWithoutTwoCompetingVotes() {
        val stable = classifyStableNumberState(
            listOf(
                StableStateVote(0L, 4), StableStateVote(500L, 4), StableStateVote(1_000L, 4),
                StableStateVote(1_500L, null), StableStateVote(2_000L, null)
            )
        )
        val unstable = classifyStableNumberState(
            listOf(
                StableStateVote(0L, 4), StableStateVote(500L, 4), StableStateVote(1_000L, 4),
                StableStateVote(1_500L, 9), StableStateVote(2_000L, 9)
            )
        )
        assertTrue(stable.stable)
        assertEquals(4, stable.value)
        assertFalse(unstable.stable)
    }

    @Test
    fun adaptiveVisualThresholdResistsIsolatedNoise() {
        val threshold = adaptiveVisualThreshold(listOf(1f, 1.1f, 0.9f, 1f, 14f))
        assertTrue(threshold.threshold >= 8f)
        assertTrue(threshold.threshold < 14f)
    }

    @Test
    fun timedOutOrInvalidNullVotesCannotBecomeStableNull() {
        val unstable = classifyStableNumberState(
            listOf(
                StableStateVote(0L, null, 0L, "OCR_TIMEOUT"),
                StableStateVote(500L, null, 500L, "INVALID_FRAME"),
                StableStateVote(1_000L, null, 1_000L, "OCR_TIMEOUT"),
                StableStateVote(1_500L, null, 1_500L, "NO_TRANSITION"),
                StableStateVote(2_000L, null, 2_000L, "NO_TRANSITION")
            )
        )
        assertFalse(unstable.stable)
    }

    @Test
    fun ambiguousTopologyCannotBecomeStableNullButNoTextCan() {
        val ambiguous = classifyStableNumberState(
            listOf(
                StableStateVote(0L, null, 0L, "AMBIGUOUS_TOPOLOGY", mlKitValue = 6),
                StableStateVote(500L, null, 500L, "AMBIGUOUS_TOPOLOGY", mlKitValue = 9),
                StableStateVote(1_000L, null, 1_000L, "AMBIGUOUS_TOPOLOGY", mlKitValue = 6),
                StableStateVote(1_500L, null, 1_500L, "NO_TEXT"),
                StableStateVote(2_000L, null, 2_000L, "NO_TEXT")
            )
        )
        val stableNull = classifyStableNumberState(
            listOf(
                StableStateVote(0L, null, 0L, "NO_TEXT"),
                StableStateVote(500L, null, 500L, "NO_TEXT"),
                StableStateVote(1_000L, null, 1_000L, "NO_TEXT"),
                StableStateVote(1_500L, null, 1_500L, "AMBIGUOUS_TOPOLOGY", mlKitValue = 6),
                StableStateVote(2_000L, null, 2_000L, "OCR_TIMEOUT")
            )
        )
        assertFalse(ambiguous.stable)
        assertTrue(stableNull.stable)
        assertEquals(null, stableNull.value)
    }

    @Test
    fun adjudicatedSixVotesStabilizeWithoutLosingRawMlKitOrDecodedTime() {
        val topology = GlyphTopologyEvidence(
            decision = SixNineDecision.SIX,
            thresholdMethod = "otsu+average",
            thresholdValue = 100,
            polarity = "bright-foreground",
            componentArea = 200,
            componentBounds = RectLike(2, 2, 18, 30),
            holeCount = 1,
            dominantHoleArea = 30,
            dominantHoleCentroidYNormalized = 0.68f,
            confidence = 0.94f,
            reason = "test"
        )
        val votes = listOf(
            StableStateVote(0L, 6, 11L, mlKitValue = 9, topology = topology),
            StableStateVote(500L, 6, 511L, mlKitValue = 6, topology = topology),
            StableStateVote(1_000L, 6, 1_011L, mlKitValue = 9, topology = topology),
            StableStateVote(1_500L, null, 1_511L, "AMBIGUOUS_TOPOLOGY", mlKitValue = 9),
            StableStateVote(2_000L, null, 2_011L, "NO_TEXT")
        )
        val state = classifyStableNumberState(votes)
        assertTrue(state.stable)
        assertEquals(6, state.value)
        assertEquals(listOf(9, 6, 9), state.votes.take(3).map { it.mlKitValue })
        assertEquals(1_011L, state.votes[2].decodedTimestampMs)
    }

    @Test
    fun semanticPolicyRejectsUnknownOrBackwardsTransitions() {
        assertFalse(classifyTransition(null, 6).sequential)
        assertFalse(classifyTransition(9, 6).sequential)
        assertTrue(classifyTransition(null, 1).sequential)
        assertTrue(classifyTransition(6, 7).sequential)
    }

    @Test
    fun recursiveInvestigationFindsMultipleTransitionsInsideOneCoarseInterval() = runBlocking {
        val boundaries = listOf(10_000L, 24_000L, 41_000L)
        fun stateAt(timeMs: Long): NumberStatePoint {
            val value = when {
                timeMs < boundaries[0] -> null
                timeMs < boundaries[1] -> 1
                timeMs < boundaries[2] -> 2
                else -> 3
            }
            return NumberStatePoint(timeMs, value, true)
        }
        val result = investigateStateInterval(
            left = stateAt(0L),
            right = stateAt(60_000L),
            minLeafMs = 2_000L,
            maxDepth = 6,
            maxProbes = 31,
            sample = { stateAt(it) }
        )
        assertEquals(listOf(1, 2, 3), result.intervals.map { it.toNumber })
        result.intervals.zip(boundaries).forEach { (interval, boundary) ->
            assertTrue(boundary in interval.startMs..interval.endMs)
            assertTrue(interval.endMs - interval.startMs <= 2_000L)
        }
    }

    @Test
    fun unstableProbeDoesNotCancelSiblingSemanticTransition() = runBlocking {
        val result = investigateStateInterval(
            left = NumberStatePoint(0L, null, true),
            right = NumberStatePoint(8_000L, 1, true),
            minLeafMs = 2_000L,
            maxDepth = 4,
            maxProbes = 7,
            sample = { timeMs ->
                if (timeMs == 4_000L) NumberStatePoint(timeMs, null, false)
                else NumberStatePoint(timeMs, if (timeMs < 3_000L) null else 1, true)
            }
        )
        assertTrue(result.probes > 1)
        assertTrue(result.intervals.any { it.fromNumber == null && it.toNumber == 1 })
    }

    @Test
    fun firstStableNumberUsesBoundedPersistentBoundaryRefinement() = runBlocking {
        val result = refinePersistentStateBoundary(
            startMs = 0L,
            endMs = 60_000L,
            targetNumber = 1,
            durationMs = 60_000L,
            sample = { timeMs -> if (timeMs >= 30_000L) 1 else null }
        )

        assertTrue(result.samples <= 11)
        assertTrue(result.timeMs in 30_000L..30_250L)
    }

    @Test
    fun randomizedSyntheticTimelinesRecoverSequentialStateChanges() = runBlocking {
        val random = Random(17_023)
        repeat(40) {
            val boundaries = (1..3)
                .map { random.nextLong(4_000L, 56_000L) }
                .sorted()
                .fold(emptyList<Long>()) { accepted, candidate ->
                    if (accepted.lastOrNull()?.let { candidate - it < 4_000L } == true) accepted
                    else accepted + candidate
                }
            fun stateAt(timeMs: Long): NumberStatePoint {
                val value = boundaries.count { timeMs >= it }.takeIf { it > 0 }
                return NumberStatePoint(timeMs, value, true)
            }

            val result = investigateStateInterval(
                left = stateAt(0L),
                right = stateAt(60_000L),
                minLeafMs = 500L,
                maxDepth = 8,
                maxProbes = 255,
                sample = { stateAt(it) }
            )

            assertEquals(boundaries.size, result.intervals.size)
            result.intervals.zip(boundaries).forEach { (interval, boundary) ->
                assertTrue(boundary in interval.startMs..interval.endMs)
            }
        }
    }
}
