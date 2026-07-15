package com.example.compilationmaker

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

data class RecognitionPoint(val x: Int, val y: Int)

data class RecognitionBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class MlKitSymbolEvidence(
    val text: String,
    val confidence: Float?,
    val bounds: RecognitionBounds?,
    val cornerPoints: List<RecognitionPoint>,
    val angleDegrees: Float
)

data class MlKitElementEvidence(
    val text: String,
    val confidence: Float?,
    val bounds: RecognitionBounds?,
    val cornerPoints: List<RecognitionPoint>,
    val angleDegrees: Float,
    val symbols: List<MlKitSymbolEvidence>
)

data class MlKitRecognitionEvidence(
    val parsedValue: Int,
    val matchedText: String,
    val blockIndex: Int,
    val lineIndex: Int,
    val matchedElementIndex: Int?,
    val lineConfidence: Float?,
    val aggregateConfidence: Float,
    val confidenceSource: String,
    val lineBounds: RecognitionBounds?,
    val lineCornerPoints: List<RecognitionPoint>,
    val lineAngleDegrees: Float,
    val elements: List<MlKitElementEvidence>
) {
    fun primaryGlyphBounds(): RecognitionBounds? {
        val matchedElement = matchedElementIndex?.let(elements::getOrNull)
        val symbols = matchedElement?.symbols ?: elements.flatMap { it.symbols }
        return if (matchedText.length == 1) {
            symbols.singleOrNull()?.bounds ?: matchedElement?.bounds ?: elements.singleOrNull()?.bounds ?: lineBounds
        } else {
            matchedElement?.bounds ?: lineBounds
        }
    }
}

data class DigitRecognition(
    val value: Int?,
    val rawText: String,
    val confidence: Float,
    val branch: String,
    val status: DigitRecognitionStatus = when {
        value != null -> DigitRecognitionStatus.PARSED
        rawText.isBlank() -> DigitRecognitionStatus.NO_TEXT
        else -> DigitRecognitionStatus.NO_VALID_INTEGER
    },
    val structure: MlKitRecognitionEvidence? = null,
    val elapsedMs: Long = 0L
)

enum class DigitRecognitionStatus { PARSED, NO_TEXT, NO_VALID_INTEGER, TIMEOUT, ML_KIT_FAILURE }

/** Centralized, bounded confirmation timeouts. Overall work scales with candidates but stays capped. */
internal object ConfirmationTimeoutPolicy {
    const val FRAME_EXTRACTION_MS = 3_000L
    const val OCR_ATTEMPT_MS = 3_500L
    const val CANDIDATE_MIN_MS = 16_000L
    const val CANDIDATE_BASE_MS = 6_000L
    const val CANDIDATE_PER_WORK_UNIT_MS = 3_000L
    const val CANDIDATE_CAP_MS = 48_000L
    const val CHECKPOINT_BOUNDARY_WORK_UNITS = 11
    const val GENERIC_CANDIDATE_WORK_UNITS = 5
    const val OVERALL_BASE_MS = 8_000L
    const val OVERALL_PER_CANDIDATE_MS = 11_000L
    const val OVERALL_CAP_MS = 180_000L

    fun overallMs(candidateCount: Int): Long =
        (OVERALL_BASE_MS + candidateCount.coerceAtLeast(0) * OVERALL_PER_CANDIDATE_MS)
            .coerceAtMost(OVERALL_CAP_MS)

    /** Wall guard scales with bounded OCR work instead of assuming foreground CPU speed. */
    fun candidateMs(plannedWorkUnits: Int): Long =
        (CANDIDATE_BASE_MS + plannedWorkUnits.coerceAtLeast(0) * CANDIDATE_PER_WORK_UNIT_MS)
            .coerceIn(CANDIDATE_MIN_MS, CANDIDATE_CAP_MS)
}

internal fun interface MonotonicTimeSource {
    fun nowMs(): Long
}

internal data class ConfirmationDeadlineSnapshot(
    val globalStartMs: Long,
    val globalDeadlineMs: Long,
    val localStartMs: Long,
    val localDeadlineMs: Long,
    val localBudgetMs: Long,
    val remainingGlobalMs: Long,
    val elapsedGlobalMs: Long,
    val elapsedLocalMs: Long,
    val localExpired: Boolean,
    val globalExpired: Boolean
)

internal fun propagatedConfirmationTimeoutSource(snapshot: ConfirmationDeadlineSnapshot): String = when {
    snapshot.localExpired -> "candidate_local"
    snapshot.globalExpired -> "global_timeout"
    else -> "nested_timeout_propagated"
}

/** Pure deadline accounting so nested operation timeouts cannot masquerade as candidate expiry. */
internal class ConfirmationDeadlineTracker(
    private val globalStartMs: Long,
    globalBudgetMs: Long,
    private val localStartMs: Long,
    private val localBudgetMs: Long,
    private val clock: MonotonicTimeSource
) {
    private val globalDeadlineMs = saturatingAdd(globalStartMs, globalBudgetMs.coerceAtLeast(0L))
    private val localDeadlineMs = saturatingAdd(localStartMs, localBudgetMs.coerceAtLeast(0L))

    fun snapshot(): ConfirmationDeadlineSnapshot {
        val nowMs = clock.nowMs()
        return ConfirmationDeadlineSnapshot(
            globalStartMs = globalStartMs,
            globalDeadlineMs = globalDeadlineMs,
            localStartMs = localStartMs,
            localDeadlineMs = localDeadlineMs,
            localBudgetMs = localBudgetMs,
            remainingGlobalMs = (globalDeadlineMs - nowMs).coerceAtLeast(0L),
            elapsedGlobalMs = (nowMs - globalStartMs).coerceAtLeast(0L),
            elapsedLocalMs = (nowMs - localStartMs).coerceAtLeast(0L),
            localExpired = nowMs >= localDeadlineMs,
            globalExpired = nowMs >= globalDeadlineMs
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}

internal object OcrPreparationPolicy {
    const val MIN_RECOGNITION_SIDE_PX = 128
    const val MAX_RECOGNITION_SIDE_PX = 512
    const val ROI_PADDING_FRACTION = 0.12f

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val minScale = max(
            MIN_RECOGNITION_SIDE_PX.toFloat() / width,
            MIN_RECOGNITION_SIDE_PX.toFloat() / height
        )
        val maxScale = min(
            MAX_RECOGNITION_SIDE_PX.toFloat() / width,
            MAX_RECOGNITION_SIDE_PX.toFloat() / height
        )
        val scale = when {
            width < MIN_RECOGNITION_SIDE_PX || height < MIN_RECOGNITION_SIDE_PX -> minScale
            width > MAX_RECOGNITION_SIDE_PX || height > MAX_RECOGNITION_SIDE_PX -> maxScale
            else -> 1f
        }
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }
}

internal fun shouldTryNextOcrVariant(attemptIndex: Int, recognition: DigitRecognition): Boolean = when {
    recognition.value != null && recognition.confidence >= 0.95f -> false
    recognition.status == DigitRecognitionStatus.TIMEOUT -> false
    recognition.status == DigitRecognitionStatus.ML_KIT_FAILURE -> false
    attemptIndex >= 3 -> false
    else -> true
}

internal fun mlKitFailureIsCandidateLocal(errorCode: Int): Boolean = errorCode == 13 || errorCode >= 0

interface DigitRecognizer : AutoCloseable {
    val name: String
    suspend fun recognize(bitmap: Bitmap, branch: String = "raw"): DigitRecognition
}

class MlKitDigitRecognizer(context: Context) : DigitRecognizer {
    private val appContext = context.applicationContext
    private val gate = Mutex()
    private var recognizer = createRecognizer()
    private var closed = false
    override val name: String = "mlkit-text-recognition"

    override suspend fun recognize(bitmap: Bitmap, branch: String): DigitRecognition {
        require(!bitmap.isRecycled) { "OCR bitmap is recycled" }
        require(bitmap.width > 0 && bitmap.height > 0) { "OCR bitmap is empty" }
        return gate.withLock {
            try {
                recognizeOnce(bitmap, branch, attempt = 1)
            } catch (timeout: OcrRecognitionTimeoutException) {
                AppLog.w(appContext, "OCR", "[ocr] attempt timed out branch=$branch; candidate will continue without retry", timeout)
                DigitRecognition(null, "", 0f, branch, DigitRecognitionStatus.TIMEOUT)
            } catch (mlKit: MlKitException) {
                AppLog.w(appContext, "OCR", "[ocr] ML Kit failure is candidate-local branch=$branch errorCode=${mlKit.errorCode}", mlKit)
                DigitRecognition(null, "", 0f, branch, DigitRecognitionStatus.ML_KIT_FAILURE)
            }
        }
    }

    private suspend fun recognizeOnce(bitmap: Bitmap, branch: String, attempt: Int): DigitRecognition {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognitionStarted = android.os.SystemClock.elapsedRealtime()
        val result = try {
            withTimeout(ConfirmationTimeoutPolicy.OCR_ATTEMPT_MS) {
                AppLog.d(appContext, "OCR", "[ocr] bitmap dimensions=${bitmap.width}x${bitmap.height} config=${bitmap.config} branch=$branch attempt=$attempt")
                val task = recognizer.process(image)
                AppLog.d(appContext, "OCR", "[ocr] process submitted branch=$branch attempt=$attempt")
                awaitTask(task, recognitionStarted, branch, attempt)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw OcrRecognitionTimeoutException(branch, timeout)
        }
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - recognitionStarted
        val text = result.text.trim()
        val numericPattern = Regex("^[0-9]{1,3}$")
        var structure: MlKitRecognitionEvidence? = null
        search@ for ((blockIndex, block) in result.textBlocks.withIndex()) {
            for ((lineIndex, line) in block.lines.withIndex()) {
                val lineText = line.text.trim()
                val numericElementIndex = if (lineText.matches(numericPattern)) {
                    null
                } else {
                    line.elements.indexOfFirst { it.text.trim().matches(numericPattern) }
                        .takeIf { it >= 0 && line.elements.size == 1 }
                }
                val matchedText = when {
                    lineText.matches(numericPattern) -> lineText
                    numericElementIndex != null -> line.elements[numericElementIndex].text.trim()
                    else -> continue
                }
                val value = matchedText.toIntOrNull() ?: continue
                val elementEvidence = line.elements.map { element ->
                    MlKitElementEvidence(
                        text = element.text,
                        confidence = actualConfidence(element.confidence),
                        bounds = element.boundingBox?.let(::recognitionBounds),
                        cornerPoints = element.cornerPoints?.map { RecognitionPoint(it.x, it.y) }.orEmpty(),
                        angleDegrees = element.angle,
                        symbols = element.symbols.map { symbol ->
                            MlKitSymbolEvidence(
                                text = symbol.text,
                                confidence = actualConfidence(symbol.confidence),
                                bounds = symbol.boundingBox?.let(::recognitionBounds),
                                cornerPoints = symbol.cornerPoints?.map { RecognitionPoint(it.x, it.y) }.orEmpty(),
                                angleDegrees = symbol.angle
                            )
                        }
                    )
                }
                val participating = numericElementIndex?.let { listOf(elementEvidence[it]) } ?: elementEvidence
                val symbolConfidences = participating.flatMap { element ->
                    element.symbols.mapNotNull { it.confidence }
                }
                val elementConfidences = participating.mapNotNull { it.confidence }
                val lineConfidence = actualConfidence(line.confidence)
                val (confidence, source) = when {
                    symbolConfidences.isNotEmpty() -> symbolConfidences.minOrNull()!! to "symbol-min"
                    elementConfidences.isNotEmpty() -> elementConfidences.minOrNull()!! to "element-min"
                    lineConfidence != null -> lineConfidence to "line"
                    else -> 0f to "unavailable"
                }
                structure = MlKitRecognitionEvidence(
                    parsedValue = value,
                    matchedText = matchedText,
                    blockIndex = blockIndex,
                    lineIndex = lineIndex,
                    matchedElementIndex = numericElementIndex,
                    lineConfidence = lineConfidence,
                    aggregateConfidence = confidence,
                    confidenceSource = source,
                    lineBounds = line.boundingBox?.let(::recognitionBounds),
                    lineCornerPoints = line.cornerPoints?.map { RecognitionPoint(it.x, it.y) }.orEmpty(),
                    lineAngleDegrees = line.angle,
                    elements = elementEvidence
                )
                break@search
            }
        }
        val value = structure?.parsedValue
        val confidence = structure?.aggregateConfidence ?: 0f
        val status = when {
            text.isBlank() -> DigitRecognitionStatus.NO_TEXT
            value == null -> DigitRecognitionStatus.NO_VALID_INTEGER
            else -> DigitRecognitionStatus.PARSED
        }
        AppLog.d(
            appContext,
            "OCR",
            "[ocr] completed branch=$branch status=$status parsed=${value ?: "none"} confidence=$confidence source=${structure?.confidenceSource ?: "none"} text=${text.take(24)}"
        )
        return DigitRecognition(value, text, confidence, branch, status, structure, elapsedMs)
    }

    private fun actualConfidence(value: Float): Float? =
        value.takeIf { it.isFinite() && it in 0f..1f }

    private fun recognitionBounds(rect: android.graphics.Rect): RecognitionBounds =
        RecognitionBounds(rect.left, rect.top, rect.right, rect.bottom)

    private suspend fun awaitTask(task: com.google.android.gms.tasks.Task<Text>, started: Long, branch: String, attempt: Int): Text =
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { result ->
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val disposition = if (result.text.isBlank()) "no-text" else "text-returned"
                AppLog.d(appContext, "OCR", "[ocr] task completed in ${elapsed} ms branch=$branch attempt=$attempt disposition=$disposition")
                if (continuation.isActive) continuation.resume(result)
            }.addOnFailureListener { failure ->
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val detail = mlKitFailureDetail(failure)
                AppLog.e(appContext, "OCR", "[ocr] task failed in ${elapsed} ms branch=$branch attempt=$attempt exception=$detail", failure)
                if (continuation.isActive) continuation.resumeWithException(failure)
            }.addOnCanceledListener {
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                AppLog.w(appContext, "OCR", "[ocr] task cancelled in ${elapsed} ms branch=$branch attempt=$attempt")
                if (continuation.isActive) continuation.cancel(CancellationException("ML Kit OCR task cancelled"))
            }
            continuation.invokeOnCancellation {
                AppLog.w(appContext, "OCR", "[ocr] task abandoned branch=$branch attempt=$attempt")
            }
        }

    private fun createRecognizer(): com.google.mlkit.vision.text.TextRecognizer {
        AppLog.d(appContext, "OCR", "[ocr] recognizer created")
        AppLog.d(appContext, "OCR", "[ocr] dependency mode=bundled")
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun mlKitFailureDetail(failure: Exception): String {
        val mlKit = failure as? MlKitException
        return if (mlKit != null) {
            "${mlKit::class.java.simpleName}(errorCode=${mlKit.errorCode}, message=${mlKit.message}, cause=${mlKit.cause})"
        } else {
            "${failure::class.java.name}(message=${failure.message}, cause=${failure.cause})"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { recognizer.close() }
            .onFailure { AppLog.w(appContext, "OCR", "[ocr] recognizer close failed", it) }
        AppLog.d(appContext, "OCR", "[ocr] recognizer closed")
    }
}

internal class OcrRecognitionTimeoutException(branch: String, cause: Throwable) :
    IllegalStateException("OCR $branch recognition timed out", cause)

fun extractDigitCandidates(rawText: String): List<Int> {
    if (rawText.isBlank()) return emptyList()
    return Regex("[-+]?[0-9]+")
        .findAll(rawText)
        .mapNotNull { it.value.toIntOrNull() }
        .toList()
}

fun binarizeForDigitRecognition(source: Bitmap, invert: Boolean = false): Bitmap {
    val scaled = if (source.width > 360 || source.height > 360) {
        val ratio = min(360f / max(1, source.width).toFloat(), 360f / max(1, source.height).toFloat())
        val targetW = max(32, (source.width * ratio).toInt())
        val targetH = max(32, (source.height * ratio).toInt())
        Bitmap.createScaledBitmap(source, targetW, targetH, true)
    } else {
        source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
    }

    val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    var sum = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        sum += (r * 30 + g * 59 + b * 11) / 100
    }
    val avg = if (pixels.isNotEmpty()) sum / pixels.size else 0
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        var luma = (r * 30 + g * 59 + b * 11) / 100
        luma = if (invert) 255 - luma else luma
        val value = if (luma >= avg) 0xffffffff.toInt() else 0xff000000.toInt()
        pixels[i] = value
    }
    output.setPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    if (scaled !== source) {
        scaled.recycle()
    }
    return output
}

fun grayscaleBitmap(source: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        val luma = (r * 30 + g * 59 + b * 11) / 100
        pixels[i] = 0xff000000.toInt() or (luma shl 16) or (luma shl 8) or luma
    }
    output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    return output
}

data class LumaPlane(val values: IntArray, val width: Int, val height: Int)

/** Android adapter only; the topology classifier itself consumes the returned pure luma array. */
fun extractTopologyLuma(
    source: Bitmap,
    recognizedBounds: RecognitionBounds?,
    paddingFraction: Float = 0.16f
): LumaPlane? {
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
    val base = recognizedBounds?.takeIf { it.width > 0 && it.height > 0 }
        ?: RecognitionBounds(0, 0, source.width, source.height)
    val padX = max(2, (base.width * paddingFraction.coerceIn(0f, 0.4f)).toInt())
    val padY = max(2, (base.height * paddingFraction.coerceIn(0f, 0.4f)).toInt())
    val left = (base.left - padX).coerceIn(0, source.width - 1)
    val top = (base.top - padY).coerceIn(0, source.height - 1)
    val right = (base.right + padX).coerceIn(left + 1, source.width)
    val bottom = (base.bottom + padY).coerceIn(top + 1, source.height)
    val width = right - left
    val height = bottom - top
    if (width < 4 || height < 6) return null

    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, left, top, width, height)
    val luma = IntArray(pixels.size)
    for (index in pixels.indices) {
        val pixel = pixels[index]
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        luma[index] = (red * 30 + green * 59 + blue * 11) / 100
    }
    return LumaPlane(luma, width, height)
}
