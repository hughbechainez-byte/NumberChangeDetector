package com.hughbechainez.numberchangedetector.scanner

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class CornerNumberTransitionDetector(
    context: Context,
    private val samplerFactory: (android.net.Uri) -> FrameSampler = { MediaRetrieverFrameSampler(context, it) },
    private val recognizerFactory: () -> DigitRecognizer = { MlKitDigitRecognizer() }
) : TransitionDetector {

    private data class RecognitionLane(
        val sampler: FrameSampler,
        val recognizer: DigitRecognizer,
        val targetFrameWidthPx: Int,
        val coarseCache: HashMap<Long, DigitEvidence> = HashMap(),
        val exactCache: HashMap<Long, DigitEvidence> = HashMap(),
        val owned: Boolean = true
    ) : AutoCloseable {
        override fun close() {
            if (!owned) return
            recognizer.close()
            sampler.close()
        }
    }

    override suspend fun detect(
        request: TransitionDetectionRequest,
        onProgress: suspend (DetectionProgress) -> Unit
    ): TransitionDetectionResult = detectWithCoreActivity(request, onProgress)

    suspend fun detectWithCoreActivity(
        request: TransitionDetectionRequest,
        onProgress: suspend (DetectionProgress) -> Unit = {},
        onCoreActivity: suspend (CoreActivityEvent) -> Unit = {}
    ): TransitionDetectionResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val warnings = ArrayList<String>()
        val decodedFrames = AtomicInteger(0)
        val ocrInferences = AtomicInteger(0)
        val roi = request.roi.normalized()

        suspend fun emitCore(event: CoreActivityEvent) {
            try {
                onCoreActivity(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Diagnostic telemetry must never change scan decisions or timestamps.
            }
        }

        samplerFactory(request.sourceUri).use { sampler ->
            recognizerFactory().use { recognizer ->
                val metadata = sampler.metadata
                require(metadata.durationMs > 0L) { "Video duration is unavailable" }
                require(metadata.encodedWidth > 0 && metadata.encodedHeight > 0) { "Video dimensions are unavailable" }
                require(metadata.rotationDegrees in setOf(0, 90, 180, 270)) { "Unsupported rotation" }

                val primaryLane = RecognitionLane(
                    sampler = sampler,
                    recognizer = recognizer,
                    targetFrameWidthPx = request.targetFrameWidthPx,
                    owned = false
                )

                suspend fun recognizeWithLane(
                    lane: RecognitionLane,
                    timeMs: Long,
                    exactPresentationTime: Boolean,
                    aggressive: Boolean
                ): DigitEvidence {
                    currentCoroutineContext().ensureActive()
                    val safeTime = timeMs.coerceIn(0L, metadata.durationMs)
                    val cache = if (exactPresentationTime) lane.exactCache else lane.coarseCache
                    val cached = cache[safeTime]
                    if (cached != null && (cached.parsedNumber != null || !aggressive)) {
                        emitCore(
                            CoreActivityEvent(
                                CoreActivityStage.OCR,
                                "cache-hit",
                                "reusing ${cached.status} value=${cached.parsedNumber ?: "none"}",
                                requestedTimeMs = safeTime,
                                actualFramePtsMs = cached.actualFramePtsMs
                            )
                        )
                        return cached
                    }

                    emitCore(
                        CoreActivityEvent(
                            CoreActivityStage.FRAME_FETCH,
                            "request",
                            "targetWidth=${lane.targetFrameWidthPx}px exactPts=$exactPresentationTime",
                            requestedTimeMs = safeTime
                        )
                    )
                    val frame = lane.sampler.frameAt(
                        safeTime,
                        lane.targetFrameWidthPx,
                        exactPresentationTime
                    )
                        ?: return DigitEvidence(
                            requestedTimeMs = safeTime,
                            actualFramePtsMs = null,
                            parsedNumber = null,
                            rawText = "",
                            confidence = 0f,
                            branch = "none",
                            status = DigitRecognitionStatus.INVALID_FRAME,
                            elapsedMs = 0L
                        ).also {
                            emitCore(
                                CoreActivityEvent(
                                    CoreActivityStage.FRAME_FETCH,
                                    "missing",
                                    "decoder returned no frame",
                                    requestedTimeMs = safeTime
                                )
                            )
                            cache[safeTime] = it
                        }
                    decodedFrames.incrementAndGet()
                    emitCore(
                        CoreActivityEvent(
                            CoreActivityStage.FRAME_FETCH,
                            "decoded",
                            "frame=${frame.bitmap.width}x${frame.bitmap.height}",
                            requestedTimeMs = safeTime,
                            actualFramePtsMs = frame.presentationTimeMs
                        )
                    )
                    try {
                        emitCore(
                            CoreActivityEvent(
                                CoreActivityStage.PREPROCESS,
                                "crop-roi",
                                "roi=${roi.xFraction},${roi.yFraction},${roi.widthFraction},${roi.heightFraction}",
                                requestedTimeMs = safeTime,
                                actualFramePtsMs = frame.presentationTimeMs
                            )
                        )
                        val cropped = crop(frame.bitmap, roi)
                        try {
                            val prepared = prepareForOcr(cropped)
                            try {
                                emitCore(
                                    CoreActivityEvent(
                                        CoreActivityStage.PREPROCESS,
                                        "ocr-ready",
                                        "crop=${cropped.width}x${cropped.height} input=${prepared.width}x${prepared.height}",
                                        requestedTimeMs = safeTime,
                                        actualFramePtsMs = frame.presentationTimeMs
                                    )
                                )
                                emitCore(
                                    CoreActivityEvent(
                                        CoreActivityStage.OCR,
                                        "infer",
                                        "aggressive=$aggressive",
                                        requestedTimeMs = safeTime,
                                        actualFramePtsMs = frame.presentationTimeMs
                                    )
                                )
                                val inferenceCountBefore = lane.recognizer.inferenceCount
                                val recognition = lane.recognizer.recognize(prepared, aggressive)
                                ocrInferences.addAndGet((lane.recognizer.inferenceCount - inferenceCountBefore).coerceAtLeast(0))
                                emitCore(
                                    CoreActivityEvent(
                                        CoreActivityStage.OCR,
                                        "result",
                                        "status=${recognition.status} value=${recognition.value ?: "none"} branch=${recognition.branch} elapsed=${recognition.elapsedMs}ms",
                                        requestedTimeMs = safeTime,
                                        actualFramePtsMs = frame.presentationTimeMs
                                    )
                                )
                                return DigitEvidence(
                                    requestedTimeMs = safeTime,
                                    actualFramePtsMs = frame.presentationTimeMs.takeIf { exactPresentationTime },
                                    parsedNumber = recognition.value,
                                    rawText = recognition.rawText.take(80),
                                    confidence = recognition.confidence,
                                    branch = recognition.branch,
                                    status = recognition.status,
                                    elapsedMs = recognition.elapsedMs,
                                    topologyDecision = recognition.topologyDecision?.name
                                ).also { cache[safeTime] = it }
                            } finally {
                                if (prepared !== cropped && !prepared.isRecycled) prepared.recycle()
                            }
                        } finally {
                            if (cropped !== frame.bitmap && !cropped.isRecycled) cropped.recycle()
                        }
                    } finally {
                        frame.close()
                    }
                }

                suspend fun recognizeAt(timeMs: Long, exactPresentationTime: Boolean, aggressive: Boolean): DigitEvidence =
                    recognizeWithLane(primaryLane, timeMs, exactPresentationTime, aggressive)

                onProgress(DetectionProgress("preflight", "Opening video and warming bundled OCR", 2))
                recognizeAt(0L, exactPresentationTime = false, aggressive = true)

                suspend fun sampleCheckpoints(
                    intervalMs: Long,
                    progressStart: Int,
                    progressSpan: Int,
                    label: String,
                    lane: RecognitionLane = primaryLane
                ): List<StatePoint> {
                    val times = generateCheckpointTimestamps(metadata.durationMs, intervalMs)
                    return times.mapIndexed { index, timeMs ->
                        val evidence = recognizeWithLane(
                            lane,
                            timeMs,
                            exactPresentationTime = false,
                            aggressive = true
                        )
                        val percent = progressStart +
                            ((index + 1) * progressSpan / times.size.coerceAtLeast(1))
                        onProgress(
                            DetectionProgress(
                                "coarse_scan",
                                "$label ${index + 1}/${times.size} at ${formatTimestampMs(timeMs)}: ${evidence.parsedNumber ?: "none"}",
                                percent.coerceIn(0, 98)
                            )
                        )
                        StatePoint(timeMs, evidence.parsedNumber, evidence)
                    }
                }

                suspend fun refineAll(
                    brackets: List<TransitionBracket>,
                    progressStart: Int,
                    progressSpan: Int,
                    baseLane: RecognitionLane = primaryLane
                ): Pair<List<TransitionMark>, List<TransitionBracket>> {
                    val confirmed = ArrayList<TransitionMark>()
                    val failed = ArrayList<TransitionBracket>()

                    suspend fun refineOne(index: Int, bracket: TransitionBracket, lane: RecognitionLane): TransitionMark? {
                        currentCoroutineContext().ensureActive()
                        val percent = progressStart +
                            ((index + 1) * progressSpan / brackets.size.coerceAtLeast(1))
                        onProgress(
                            DetectionProgress(
                                "refining",
                                "Refining ${bracket.fromNumber ?: "none"} -> ${bracket.toNumber} (${index + 1}/${brackets.size})",
                                percent.coerceIn(0, 98)
                            )
                        )
                        return refineBracket(
                            bracket = bracket,
                            sampler = lane.sampler,
                            durationMs = metadata.durationMs,
                            recognize = { timeMs, exact, aggressive ->
                                recognizeWithLane(lane, timeMs, exact, aggressive)
                            },
                            emitCore = ::emitCore,
                            candidateIndex = index + 1,
                            candidateCount = brackets.size
                        )
                    }

                    if (brackets.isEmpty()) return confirmed to failed
                    val laneCount = request.maxParallelRefinements.coerceIn(1, min(3, brackets.size))
                    val lanes = ArrayList<RecognitionLane>(laneCount).apply {
                        add(baseLane)
                        repeat(laneCount - 1) {
                            add(
                                RecognitionLane(
                                    sampler = samplerFactory(request.sourceUri),
                                    recognizer = recognizerFactory(),
                                    targetFrameWidthPx = baseLane.targetFrameWidthPx
                                )
                            )
                        }
                    }
                    if (laneCount > 1) {
                        emitCore(
                            CoreActivityEvent(
                                CoreActivityStage.PTS_CONFIRMATION,
                                "parallel-refinement",
                                "$laneCount independent decoder/OCR lanes"
                            )
                        )
                    }
                    val outcomes = try {
                        coroutineScope {
                            lanes.mapIndexed { laneIndex, lane ->
                                async(Dispatchers.Default) {
                                    brackets.withIndex()
                                        .filter { it.index % laneCount == laneIndex }
                                        .map { indexed -> indexed.index to refineOne(indexed.index, indexed.value, lane) }
                                }
                            }.awaitAll().flatten().sortedBy { it.first }
                        }
                    } finally {
                        lanes.filter { it !== baseLane }.forEach(RecognitionLane::close)
                    }
                    outcomes.forEach { (index, mark) ->
                        val bracket = brackets[index]
                        if (mark == null) {
                            failed += bracket
                        } else if (confirmed.none {
                                it.fromNumber == mark.fromNumber && it.toNumber == mark.toNumber &&
                                    abs(it.eventBoundaryMs - mark.eventBoundaryMs) <= 1_000L
                            }) {
                            confirmed += mark
                        }
                    }
                    return confirmed to failed
                }

                suspend fun standardFastFallback(reason: String): Triple<List<StatePoint>, List<TransitionBracket>, List<TransitionMark>> {
                    warnings += "Monotonic turbo fell back to the proven 30-second scan: $reason"
                    emitCore(
                        CoreActivityEvent(
                            CoreActivityStage.MONOTONIC_PLANNING,
                            "fallback",
                            reason
                        )
                    )
                    onProgress(DetectionProgress("coarse_scan", "Monotonic safety fallback: $reason", 81))
                    val fallbackLane = RecognitionLane(
                        sampler = samplerFactory(request.sourceUri),
                        recognizer = recognizerFactory(),
                        targetFrameWidthPx = request.fallbackFrameWidthPx
                    )
                    return try {
                        emitCore(
                            CoreActivityEvent(
                                CoreActivityStage.MONOTONIC_PLANNING,
                                "fallback-full-resolution",
                                "fresh ${request.fallbackFrameWidthPx}px decoder/OCR lane"
                            )
                        )
                        val fallbackTimeline = despikeStatePoints(
                            sampleCheckpoints(
                                ScanProfile.FAST.checkpointIntervalMs,
                                progressStart = 81,
                                progressSpan = 9,
                                label = "Fallback checkpoint",
                                lane = fallbackLane
                            )
                        )
                        val fallbackBrackets = buildTransitionBrackets(fallbackTimeline)
                        if (fallbackBrackets.any { it.fromNumber != null && it.toNumber != it.fromNumber + 1 }) {
                            warnings += "One or more fallback coarse states were non-sequential; inspect those rows before integration."
                        }
                        val (fallbackMarks, failedFallback) = refineAll(
                            fallbackBrackets,
                            progressStart = 91,
                            progressSpan = 7,
                            baseLane = fallbackLane
                        )
                        failedFallback.forEach { bracket ->
                            warnings += "Could not persistently confirm ${bracket.fromNumber ?: "none"} -> ${bracket.toNumber} in ${formatTimestampMs(bracket.startMs)}..${formatTimestampMs(bracket.endMs)}"
                        }
                        Triple(fallbackTimeline, fallbackBrackets, fallbackMarks)
                    } finally {
                        fallbackLane.close()
                    }
                }

                val finalTimeline: List<StatePoint>
                val finalBrackets: List<TransitionBracket>
                val marks: List<TransitionMark>

                if (request.profile == ScanProfile.MONOTONIC_3_MIN || request.profile == ScanProfile.QUICK_5_MIN) {
                    val macroTimeline = sampleCheckpoints(
                        request.profile.checkpointIntervalMs,
                        progressStart = 5,
                        progressSpan = 40,
                        label = "Monotonic checkpoint"
                    )
                    var adaptiveProbeOrdinal = 0
                    val plan = planMonotonicTimeline(macroTimeline) { timeMs ->
                        adaptiveProbeOrdinal++
                        emitCore(
                            CoreActivityEvent(
                                CoreActivityStage.MONOTONIC_PLANNING,
                                "probe",
                                "adaptive probe $adaptiveProbeOrdinal at ${formatTimestampMs(timeMs)}",
                                requestedTimeMs = timeMs
                            )
                        )
                        onProgress(
                            DetectionProgress(
                                "coarse_scan",
                                "Adaptive monotonic probe $adaptiveProbeOrdinal at ${formatTimestampMs(timeMs)}",
                                (45 + adaptiveProbeOrdinal.coerceAtMost(14)).coerceAtMost(59)
                            )
                        )
                        val evidence = recognizeAt(timeMs, exactPresentationTime = false, aggressive = true)
                        emitCore(
                            CoreActivityEvent(
                                CoreActivityStage.MONOTONIC_PLANNING,
                                "observed",
                                "adaptive probe $adaptiveProbeOrdinal value=${evidence.parsedNumber ?: "none"}",
                                requestedTimeMs = timeMs
                            )
                        )
                        StatePoint(timeMs, evidence.parsedNumber, evidence)
                    }

                    when (plan) {
                        is MonotonicTimelinePlan.Fallback -> {
                            val fallback = standardFastFallback(plan.reason)
                            finalTimeline = fallback.first
                            finalBrackets = fallback.second
                            marks = fallback.third
                        }
                        is MonotonicTimelinePlan.Success -> {
                            val monotonicTimeline = plan.points
                            val monotonicBrackets = buildTransitionBrackets(monotonicTimeline)
                            val (monotonicMarks, failedMonotonic) = refineAll(
                                monotonicBrackets,
                                progressStart = 60,
                                progressSpan = 20
                            )
                            val violation = failedMonotonic.firstOrNull()?.let { bracket ->
                                "persistent refinement failed for ${bracket.fromNumber ?: "none"}->${bracket.toNumber}"
                            } ?: monotonicRefinementViolation(monotonicBrackets, monotonicMarks)
                            if (violation != null) {
                                val fallback = standardFastFallback(violation)
                                finalTimeline = fallback.first
                                finalBrackets = fallback.second
                                marks = fallback.third
                            } else {
                                emitCore(
                                    CoreActivityEvent(
                                        CoreActivityStage.MONOTONIC_PLANNING,
                                        "accepted",
                                        "${plan.adaptiveProbeCount} adaptive probes confirmed ${monotonicMarks.size} transitions"
                                    )
                                )
                                finalTimeline = monotonicTimeline
                                finalBrackets = monotonicBrackets
                                marks = monotonicMarks
                            }
                        }
                    }
                } else {
                    val coarse = sampleCheckpoints(
                        request.profile.checkpointIntervalMs,
                        progressStart = 5,
                        progressSpan = 60,
                        label = "Checkpoint"
                    )
                    finalTimeline = despikeStatePoints(coarse)
                    finalBrackets = buildTransitionBrackets(finalTimeline)
                    if (finalBrackets.any { it.fromNumber != null && it.toNumber != it.fromNumber + 1 }) {
                        warnings += "One or more coarse states were non-sequential; inspect those rows before integration."
                    }
                    val (standardMarks, failedStandard) = refineAll(
                        finalBrackets,
                        progressStart = 65,
                        progressSpan = 33
                    )
                    failedStandard.forEach { bracket ->
                        warnings += "Could not persistently confirm ${bracket.fromNumber ?: "none"} -> ${bracket.toNumber} in ${formatTimestampMs(bracket.startMs)}..${formatTimestampMs(bracket.endMs)}"
                    }
                    marks = standardMarks
                }

                val wallMs = android.os.SystemClock.elapsedRealtime() - started
                val metrics = ScanMetrics(
                    scannerVersion = SCANNER_VERSION,
                    wallClockMs = wallMs,
                    checkpointCount = finalTimeline.size,
                    decodedFrameCount = decodedFrames.get(),
                    ocrInferenceCount = ocrInferences.get(),
                    candidateCount = finalBrackets.size,
                    confirmedTransitionCount = marks.size,
                    videoToWallSpeed = if (wallMs > 0L) metadata.durationMs.toFloat() / wallMs else 0f
                )
                onProgress(DetectionProgress("completed", "Found ${marks.size} transitions", 100))
                return TransitionDetectionResult(
                    sourceUri = request.sourceUri.toString(),
                    videoDurationMs = metadata.durationMs,
                    roi = roi,
                    profile = request.profile,
                    transitions = marks.sortedBy { it.eventBoundaryMs },
                    checkpoints = finalTimeline,
                    metrics = metrics,
                    warnings = warnings
                )
            }
        }
    }

    private suspend fun refineBracket(
        bracket: TransitionBracket,
        sampler: FrameSampler,
        durationMs: Long,
        recognize: suspend (Long, Boolean, Boolean) -> DigitEvidence,
        emitCore: suspend (CoreActivityEvent) -> Unit,
        candidateIndex: Int,
        candidateCount: Int
    ): TransitionMark? {
        emitCore(
            CoreActivityEvent(
                CoreActivityStage.PTS_ENUMERATION,
                "candidate-start",
                "candidate=$candidateIndex/$candidateCount ${bracket.fromNumber ?: "none"}->${bracket.toNumber} range=${formatTimestampMs(bracket.startMs)}..${formatTimestampMs(bracket.endMs)}",
                requestedTimeMs = bracket.startMs
            )
        )
        val enumerationEndMs = if (bracket.startMs == bracket.endMs) {
            (bracket.endMs + FIRST_STATE_CONFIRMATION_WINDOW_MS)
                .coerceAtMost(durationMs)
        } else {
            bracket.endMs
        }
        var sampleTimes = sampler.presentationTimesBetween(bracket.startMs, enumerationEndMs)
            .filter { it in bracket.startMs..enumerationEndMs }
            .distinct()
            .sorted()
        emitCore(
            CoreActivityEvent(
                CoreActivityStage.PTS_ENUMERATION,
                "samples",
                "candidate=$candidateIndex/$candidateCount extractorPts=${sampleTimes.size}",
                requestedTimeMs = bracket.startMs
            )
        )
        if (sampleTimes.isEmpty()) {
            sampleTimes = generateFallbackTimes(bracket.startMs, bracket.endMs, durationMs)
            emitCore(
                CoreActivityEvent(
                    CoreActivityStage.PTS_ENUMERATION,
                    "fallback-grid",
                    "candidate=$candidateIndex/$candidateCount samples=${sampleTimes.size}",
                    requestedTimeMs = bracket.startMs
                )
            )
        }
        if (sampleTimes.isEmpty()) return null
        if (!hasIndependentBoundaryConfirmationSamples(bracket, sampleTimes.size)) return null

        val observed = LinkedHashMap<Long, DigitEvidence>()
        suspend fun valueAt(index: Int): Int? {
            val time = sampleTimes[index]
            observed[time]?.let { return it.parsedNumber }
            emitCore(
                CoreActivityEvent(
                    CoreActivityStage.PTS_CONFIRMATION,
                    "sample",
                    "candidate=$candidateIndex/$candidateCount index=$index/${sampleTimes.lastIndex}",
                    requestedTimeMs = time,
                    actualFramePtsMs = time
                )
            )
            val evidence = recognize(time, true, true)
            observed[time] = evidence
            emitCore(
                CoreActivityEvent(
                    CoreActivityStage.PTS_CONFIRMATION,
                    "observed",
                    "candidate=$candidateIndex/$candidateCount index=$index value=${evidence.parsedNumber ?: "none"} status=${evidence.status}",
                    requestedTimeMs = time,
                    actualFramePtsMs = evidence.actualFramePtsMs ?: time
                )
            )
            return evidence.parsedNumber
        }

        var low = 0
        var high = sampleTimes.lastIndex
        while (low < high) {
            val middle = low + (high - low) / 2
            emitCore(
                CoreActivityEvent(
                    CoreActivityStage.BINARY_SEARCH,
                    "probe",
                    "candidate=$candidateIndex/$candidateCount low=$low mid=$middle high=$high target=${bracket.toNumber}",
                    requestedTimeMs = sampleTimes[middle],
                    actualFramePtsMs = sampleTimes[middle]
                )
            )
            if (valueAt(middle) == bracket.toNumber) high = middle else low = middle + 1
        }

        val searchStart = max(0, low - 6)
        val searchEnd = min(sampleTimes.lastIndex, low + 8)
        val boundaryIndex = findFirstPersistentTargetIndex(
            searchStart = searchStart,
            searchEnd = searchEnd,
            sourceLastIndex = sampleTimes.lastIndex,
            targetNumber = bracket.toNumber
        ) { index ->
            emitCore(
                CoreActivityEvent(
                    CoreActivityStage.PTS_CONFIRMATION,
                    "neighborhood",
                    "candidate=$candidateIndex/$candidateCount index=$index range=$searchStart..$searchEnd",
                    requestedTimeMs = sampleTimes[index],
                    actualFramePtsMs = sampleTimes[index]
                )
            )
            valueAt(index)
        } ?: return null

        val boundaryMs = sampleTimes[boundaryIndex]
        if (boundaryMs !in bracket.startMs..bracket.endMs) return null
        val actualBoundaryPtsMs = observed[boundaryMs]?.actualFramePtsMs ?: return null
        if (actualBoundaryPtsMs !in bracket.startMs..bracket.endMs) return null
        emitCore(
            CoreActivityEvent(
                CoreActivityStage.PTS_CONFIRMATION,
                "boundary",
                "candidate=$candidateIndex/$candidateCount confirmed ${bracket.fromNumber ?: "none"}->${bracket.toNumber}",
                requestedTimeMs = boundaryMs,
                actualFramePtsMs = boundaryMs
            )
        )
        val targetEvidence = observed.values.filter {
            it.parsedNumber == bracket.toNumber && it.requestedTimeMs in boundaryMs..(boundaryMs + 1_000L)
        }
        val confidence = targetEvidence.map { it.confidence }.average().takeIf { !it.isNaN() }
            ?.toFloat()?.coerceIn(0.35f, 1f) ?: 0.5f
        val compactEvidence = observed.values
            .distinctBy { it.requestedTimeMs }
            .sortedBy { abs(it.requestedTimeMs - boundaryMs) }
            .take(12)
            .sortedBy { it.requestedTimeMs }

        return TransitionMark(
            eventBoundaryMs = actualBoundaryPtsMs,
            actualFramePtsMs = actualBoundaryPtsMs,
            fromNumber = bracket.fromNumber,
            toNumber = bracket.toNumber,
            confidence = confidence,
            evidence = compactEvidence
        )
    }

    private fun generateFallbackTimes(startMs: Long, endMs: Long, durationMs: Long): List<Long> {
        val result = ArrayList<Long>()
        var time = startMs.coerceIn(0L, durationMs)
        val end = endMs.coerceIn(time, durationMs)
        while (time < end) {
            result += time
            time += 250L
        }
        result += end
        return result.distinct()
    }

    private fun crop(source: Bitmap, window: ScanWindow): Bitmap {
        val normalized = window.normalized()
        val left = floor(source.width * normalized.xFraction).toInt().coerceIn(0, source.width - 1)
        val top = floor(source.height * normalized.yFraction).toInt().coerceIn(0, source.height - 1)
        val right = ceil(source.width * (normalized.xFraction + normalized.widthFraction)).toInt()
            .coerceIn(left + 1, source.width)
        val bottom = ceil(source.height * (normalized.yFraction + normalized.heightFraction)).toInt()
            .coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun prepareForOcr(source: Bitmap): Bitmap {
        val minSide = min(source.width, source.height)
        val maxSide = max(source.width, source.height)
        val scale = when {
            minSide < MIN_OCR_SIDE -> MIN_OCR_SIDE.toFloat() / minSide.coerceAtLeast(1)
            maxSide > MAX_OCR_SIDE -> MAX_OCR_SIDE.toFloat() / maxSide
            else -> 1f
        }
        if (scale == 1f) return source
        return Bitmap.createScaledBitmap(
            source,
            max(32, (source.width * scale).toInt()),
            max(32, (source.height * scale).toInt()),
            true
        )
    }

    private companion object {
        const val SCANNER_VERSION = "v1-sparse-pts-ocr"
        const val MIN_OCR_SIDE = 192
        const val MAX_OCR_SIDE = 512
        const val FIRST_STATE_CONFIRMATION_WINDOW_MS = 1_000L
    }
}
