package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerMathTest {
    @Test
    fun rotationTransformsRoiAsExpected() {
        val saved = ScanWindow(0.10f, 0.20f, 0.30f, 0.40f)
        val rot90 = rotateScanWindowForCurrentRotation(saved, 0, 90)
        val rot180 = rotateScanWindowForCurrentRotation(saved, 0, 180)
        val rot270 = rotateScanWindowForCurrentRotation(saved, 0, 270)

        assertEquals(0.20f, rot90.xPercent, 0.0001f)
        assertEquals(0.60f, rot90.yPercent, 0.0001f)
        assertEquals(0.40f, rot90.widthPercent, 0.0001f)
        assertEquals(0.30f, rot90.heightPercent, 0.0001f)

        assertEquals(0.60f, rot180.xPercent, 0.0001f)
        assertEquals(0.40f, rot180.yPercent, 0.0001f)
        assertEquals(0.30f, rot180.widthPercent, 0.0001f)
        assertEquals(0.40f, rot180.heightPercent, 0.0001f)

        assertEquals(0.40f, rot270.xPercent, 0.0001f)
        assertEquals(0.10f, rot270.yPercent, 0.0001f)
        assertEquals(0.40f, rot270.widthPercent, 0.0001f)
        assertEquals(0.30f, rot270.heightPercent, 0.0001f)
    }

    @Test
    fun mergeSegmentWindowsCollapsesOverlapAndGap() {
        val merged = mergeSegmentWindows(
            listOf(
                SegmentWindow(0L, 10_000L),
                SegmentWindow(9_500L, 15_000L),
                SegmentWindow(40_000L, 50_000L)
            ),
            maxGapMs = 1_000L
        )

        assertEquals(2, merged.size)
        assertEquals(SegmentWindow(0L, 15_000L), merged[0])
        assertEquals(SegmentWindow(40_000L, 50_000L), merged[1])
    }

    @Test
    fun requestedSegmentsClampToDuration() {
        val segment = buildRequestedSegment(boundaryMs = 25_000L, durationMs = 40_000L)
        assertEquals(15_000L, segment.startMs)
        assertEquals(40_000L, segment.endMs)
    }

    @Test
    fun exactPrimaryClipPlanKeepsTenUnpaddedWindowsAndFourHundredSecondDuration() {
        val boundaries = listOf(
            30_000L, 75_000L, 255_000L, 855_000L, 975_000L,
            1_275_000L, 1_395_000L, 1_995_000L, 2_415_000L, 3_560_000L
        )

        val segments = planExactTransitionSegments(boundaries, 3_600_500L)

        assertEquals(10, segments.size)
        assertEquals(SegmentWindow(20_000L, 60_000L), segments[0])
        assertEquals(SegmentWindow(65_000L, 105_000L), segments[1])
        assertEquals(400_000L, expectedCompilationDurationMs(segments))
    }

    @Test
    fun exactClipPlanMergesOnlyOverlapAndStillClampsSourceEdges() {
        val segments = planExactTransitionSegments(
            boundariesMs = listOf(5_000L, 25_000L, 95_000L),
            durationMs = 100_000L
        )

        assertEquals(
            listOf(SegmentWindow(0L, 55_000L), SegmentWindow(85_000L, 100_000L)),
            segments
        )
    }

    @Test
    fun zeroConfirmedStrongVisualEvidenceStillProducesProvisionalClips() {
        val evidence = listOf(20_000L, 70_000L, 130_000L).mapIndexed { index, timestamp ->
            ProvisionalTransitionEvidence(
                timestampMs = timestamp,
                visualScore = 12f + index,
                partialEvidence = true,
                timeoutCount = 1,
                reason = "simulated inner frame timeout"
            )
        }

        val points = selectTransitionPlanPoints(emptyList(), evidence, dedupeToleranceMs = 900L)
        val clips = planExactTransitionSegments(points.map { it.timestampMs }, durationMs = 180_000L)

        assertEquals(3, points.size)
        assertTrue(points.all { it.classification == TransitionPlanClassification.PROVISIONAL_MEDIUM })
        assertTrue(clips.isNotEmpty())
    }

    @Test
    fun confirmedAndHighProvisionalPointsDeduplicateWithoutDroppingDistinctEvidence() {
        val points = selectTransitionPlanPoints(
            confirmedTimestampsMs = listOf(10_000L),
            provisionalEvidence = listOf(
                ProvisionalTransitionEvidence(
                    timestampMs = 10_500L,
                    visualScore = 20f,
                    fromNumber = 1,
                    toNumber = 2,
                    fromStateStable = true,
                    toStateStable = true
                ),
                ProvisionalTransitionEvidence(
                    timestampMs = 50_000L,
                    visualScore = 18f,
                    fromNumber = 2,
                    toNumber = 3,
                    fromStateStable = true,
                    toStateStable = true,
                    partialEvidence = true
                ),
                ProvisionalTransitionEvidence(timestampMs = 90_000L, visualScore = 30f)
            ),
            dedupeToleranceMs = 900L
        )

        assertEquals(listOf(10_000L, 50_000L), points.map { it.timestampMs })
        assertEquals(TransitionPlanClassification.CONFIRMED, points[0].classification)
        assertEquals(TransitionPlanClassification.PROVISIONAL_HIGH, points[1].classification)
    }

    @Test
    fun confirmedCandidateIsNotReaddedWhenItsSeedIsFarFromRefinedBoundary() {
        val points = selectTransitionPlanPoints(
            confirmedTimestampsMs = listOf(255_000L),
            provisionalEvidence = listOf(
                ProvisionalTransitionEvidence(
                    timestampMs = 270_000L,
                    visualScore = 30f,
                    fromNumber = 6,
                    toNumber = 7,
                    fromStateStable = true,
                    toStateStable = true
                )
            ),
            dedupeToleranceMs = 900L,
            confirmedCandidateIndices = setOf(0)
        )

        assertEquals(listOf(255_000L), points.map { it.timestampMs })
        assertEquals(TransitionPlanClassification.CONFIRMED, points.single().classification)
        assertTrue(points.single().visualScore.isFinite())
    }

    @Test
    fun tenConfirmedAndResidualProvisionalCandidatesProduceTenExactWindows() {
        val boundaries = listOf(
            30_000L, 75_000L, 255_000L, 855_000L, 975_000L,
            1_275_000L, 1_395_000L, 1_995_000L, 2_415_000L, 3_560_000L
        )
        val strict = boundaries.take(8)
        val evidence = boundaries.mapIndexed { index, timestamp ->
            ProvisionalTransitionEvidence(
                timestampMs = timestamp + if (index < 8) 15_000L else 0L,
                visualScore = 20f + index,
                fromNumber = index,
                toNumber = index + 1,
                fromStateStable = true,
                toStateStable = true
            )
        }

        val points = selectTransitionPlanPoints(
            confirmedTimestampsMs = strict,
            provisionalEvidence = evidence,
            dedupeToleranceMs = 900L,
            confirmedCandidateIndices = (0 until 8).toSet()
        )
        val segments = planExactTransitionSegments(points.map { it.timestampMs }.sorted(), 3_600_500L)

        assertEquals(10, points.size)
        assertEquals(10, segments.size)
        assertEquals(400_000L, expectedCompilationDurationMs(segments))
        assertTrue(points.all { it.visualScore.isFinite() })
    }

    @Test
    fun suppliedTwentyOneCheckpointRegressionCannotCollapseNineteenCandidatesToNoResults() {
        val simulatedCandidates = (0 until 19).map { index ->
            ProvisionalTransitionEvidence(
                timestampMs = 30_000L + index * 60_000L,
                visualScore = 10f + index,
                partialEvidence = index < 10,
                timeoutCount = if (index < 10) 1 else 0,
                reason = "21 checkpoints / 19 candidates / zero strict confirmations"
            )
        }

        val selection = selectTransitionPlanWithBaselineFallback(
            confirmedTimestampsMs = emptyList(),
            refinedEvidence = emptyList(),
            baselineEvidence = simulatedCandidates,
            dedupeToleranceMs = 900L
        )
        val points = selection.points
        val clips = planExactTransitionSegments(points.map { it.timestampMs }, 1_200_000L)

        assertTrue(selection.baselineFallbackUsed)
        assertEquals(19, points.size)
        assertTrue(points.all { it.classification == TransitionPlanClassification.PROVISIONAL_MEDIUM })
        assertTrue(clips.isNotEmpty())
        assertTrue(expectedCompilationDurationMs(clips) > 0L)
    }

    @Test
    fun noCredibleEvidenceProducesNoPlanPoints() {
        val points = selectTransitionPlanPoints(
            confirmedTimestampsMs = emptyList(),
            provisionalEvidence = listOf(
                ProvisionalTransitionEvidence(timestampMs = -1L, visualScore = 20f),
                ProvisionalTransitionEvidence(timestampMs = 10_000L, visualScore = 0f)
            ),
            dedupeToleranceMs = 900L
        )

        assertTrue(points.isEmpty())
    }

    @Test
    fun stableNonSequentialEvidenceCanNeverBecomeAProvisionalClip() {
        val points = selectTransitionPlanPoints(
            confirmedTimestampsMs = emptyList(),
            provisionalEvidence = listOf(
                ProvisionalTransitionEvidence(
                    timestampMs = 20_000L,
                    visualScore = 100f,
                    fromNumber = 9,
                    toNumber = 6,
                    fromStateStable = true,
                    toStateStable = true,
                    partialEvidence = true
                )
            ),
            dedupeToleranceMs = 900L
        )

        assertTrue(points.isEmpty())
    }

    @Test
    fun rejectedRefinedEvidenceCanFallBackToPreservedUnstableVisualCandidates() {
        val selection = selectTransitionPlanWithBaselineFallback(
            confirmedTimestampsMs = emptyList(),
            refinedEvidence = listOf(
                ProvisionalTransitionEvidence(
                    timestampMs = 20_000L,
                    visualScore = 100f,
                    fromNumber = 9,
                    toNumber = 6,
                    fromStateStable = true,
                    toStateStable = true
                )
            ),
            baselineEvidence = listOf(
                ProvisionalTransitionEvidence(
                    timestampMs = 19_500L,
                    visualScore = 30f,
                    partialEvidence = true,
                    reason = "preserved baseline visual peak"
                )
            ),
            dedupeToleranceMs = 900L
        )

        assertTrue(selection.baselineFallbackUsed)
        assertEquals(listOf(19_500L), selection.points.map { it.timestampMs })
        assertEquals(TransitionPlanClassification.PROVISIONAL_MEDIUM, selection.points.single().classification)
    }

    @Test
    fun sourceDurationDoesNotCollapseToProcessingTime() {
        val resolved = resolveSourceDurationMs(3_600_000L, 12_345L)
        assertEquals(3_600_000L, resolved)
    }

    @Test
    fun transitionClassificationDistinguishesSequentialAndNonSequential() {
        val first = classifyTransition(null, 1)
        val sequential = classifyTransition(1, 2)
        val nonSequential = classifyTransition(4, 7)
        val unchanged = classifyTransition(5, 5)

        assertTrue(first.accepted)
        assertTrue(first.sequential)
        assertTrue(sequential.accepted)
        assertTrue(sequential.sequential)
        assertTrue(nonSequential.accepted)
        assertFalse(nonSequential.sequential)
        assertFalse(unchanged.accepted)
    }

    @Test
    fun hysteresisOpensAndClosesAfterConsecutiveSamples() {
        var decision = HysteresisDecision(open = false, consecutiveChanged = 0, consecutiveStable = 0)
        decision = updateHysteresisDecision(decision, changed = true)
        assertFalse(decision.open)
        decision = updateHysteresisDecision(decision, changed = true)
        assertTrue(decision.open)
        decision = updateHysteresisDecision(decision, changed = false)
        assertTrue(decision.open)
        decision = updateHysteresisDecision(decision, changed = false)
        assertFalse(decision.open)
    }

    @Test
    fun coarseCheckpointsAreDirectAndNeverTreatZeroAsAChange() {
        val checkpoints = generateCheckpointTimestamps(4_782_000L, 60_000L)

        assertEquals(81, checkpoints.size)
        assertEquals(0L, checkpoints.first())
        assertEquals(60_000L, checkpoints[1])
        assertEquals(4_782_000L, checkpoints.last())
        assertTrue(checkpoints.zipWithNext().all { (a, b) -> b - a == 60_000L || b == 4_782_000L })
        assertFalse(classifyTransition(null, null).accepted)
    }

    @Test
    fun videoAUsesExactlySixtyOneCheckpointsWithoutRedundantTailSeek() {
        val checkpoints = generateCheckpointTimestamps(3_600_500L, 60_000L)

        assertEquals(61, checkpoints.size)
        assertEquals(0L, checkpoints.first())
        assertEquals(3_600_000L, checkpoints.last())
    }

    @Test
    fun visualOnlyIntervalsReceiveOneProbeWhileSemanticEndpointsMayBisect() {
        assertEquals(1, checkpointInvestigationProbeLimit(false, false, null, null))
        assertEquals(1, checkpointInvestigationProbeLimit(true, false, 4, null))
        assertEquals(3, checkpointInvestigationProbeLimit(true, true, 4, 5))
    }

    @Test
    fun supportedTransitionStatesIncludeNoNumberToOneAndOneToTwo() {
        assertEquals("null -> 1", classifyTransition(null, 1).label)
        assertTrue(classifyTransition(null, 1).accepted)
        assertEquals("1 -> 2", classifyTransition(1, 2).label)
        assertTrue(classifyTransition(1, 2).accepted)
        assertFalse(classifyTransition(1, 1).accepted)
    }
}
