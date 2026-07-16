package com.hughbechainez.numberchangedetector.scanner

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        val cache = HashMap<Long, DigitEvidence>()
        var decodedFrames = 0
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

                suspend fun recognizeAt(timeMs: Long, exactPresentationTime: Boolean, aggressive: Boolean): DigitEvidence {
                    currentCoroutineContext().ensureActive()
                    val safeTime = timeMs.coerceIn(0L, metadata.durationMs)
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
                        return if (exactPresentationTime && cached.actualFramePtsMs == null) {
                            cached.copy(actualFramePtsMs = safeTime).also { cache[safeTime] = it }
                        } else cached
                    }

                    emitCore(
                        CoreActivityEvent(
                            CoreActivityStage.FRAME_FETCH,
                            "request",
                            "targetWidth=${request.targetFrameWidthPx}px exactPts=$exactPresentationTime",
                            requestedTimeMs = safeTime
                        )
                    )
                    val frame = sampler.frameAt(safeTime, request.targetFrameWidthPx)
                        ?: return DigitEvidence(
                            requestedTimeMs = safeTime,
                            actualFramePtsMs = safeTime.takeIf { exactPresentationTime },
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
                    decodedFrames++
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
                                val recognition = recognizer.recognize(prepared, aggressive)
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
                                    actualFramePtsMs = safeTime.takeIf { exactPresentationTime },
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

                onProgress(DetectionProgress("preflight", "Opening video and warming bundled OCR", 2))
                recognizeAt(0L, exactPresentationTime = false, aggressive = true)

                val checkpointTimes = generateCheckpointTimestamps(
                    metadata.durationMs,
                    request.profile.checkpointIntervalMs
                )
                val coarse = ArrayList<StatePoint>(checkpointTimes.size)
                checkpointTimes.forEachIndexed { index, timeMs ->
                    val evidence = recognizeAt(timeMs, exactPresentationTime = false, aggressive = true)
                    coarse += StatePoint(timeMs, evidence.parsedNumber, evidence)
                    val percent = 5 + ((index + 1) * 60 / checkpointTimes.size.coerceAtLeast(1))
                    onProgress(
                        DetectionProgress(
                            "coarse_scan",
                            "Checkpoint ${index + 1}/${checkpointTimes.size} at ${formatTimestampMs(timeMs)}: ${evidence.parsedNumber ?: "none"}",
                            percent
                        )
                    )
                }

                val smoothed = despikeStatePoints(coarse)
                val brackets = buildTransitionBrackets(smoothed)
                if (brackets.any { it.fromNumber != null && it.toNumber != it.fromNumber + 1 }) {
                    warnings += "One or more coarse states were non-sequential; inspect those rows before integration."
                }

                val marks = ArrayList<TransitionMark>()
                brackets.forEachIndexed { index, bracket ->
                    currentCoroutineContext().ensureActive()
                    val percent = 65 + ((index + 1) * 33 / brackets.size.coerceAtLeast(1))
                    onProgress(
                        DetectionProgress(
                            "refining",
                            "Refining ${bracket.fromNumber ?: "none"} -> ${bracket.toNumber} (${index + 1}/${brackets.size})",
                            percent
                        )
                    )
                    val mark = refineBracket(
                        bracket = bracket,
                        sampler = sampler,
                        durationMs = metadata.durationMs,
                        recognize = ::recognizeAt,
                        emitCore = ::emitCore,
                        candidateIndex = index + 1,
                        candidateCount = brackets.size
                    )
                    if (mark == null) {
                        warnings += "Could not persistently confirm ${bracket.fromNumber ?: "none"} -> ${bracket.toNumber} in ${formatTimestampMs(bracket.startMs)}..${formatTimestampMs(bracket.endMs)}"
                    } else if (marks.none {
                            it.fromNumber == mark.fromNumber && it.toNumber == mark.toNumber &&
                                abs(it.eventBoundaryMs - mark.eventBoundaryMs) <= 1_000L
                        }) {
                        marks += mark
                    }
                }

                val wallMs = android.os.SystemClock.elapsedRealtime() - started
                val metrics = ScanMetrics(
                    scannerVersion = SCANNER_VERSION,
                    wallClockMs = wallMs,
                    checkpointCount = checkpointTimes.size,
                    decodedFrameCount = decodedFrames,
                    ocrInferenceCount = recognizer.inferenceCount,
                    candidateCount = brackets.size,
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
                    checkpoints = smoothed,
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
        var sampleTimes = sampler.presentationTimesBetween(bracket.startMs, bracket.endMs)
            .filter { it in bracket.startMs..bracket.endMs }
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
            eventBoundaryMs = boundaryMs,
            actualFramePtsMs = boundaryMs,
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
    }
}
