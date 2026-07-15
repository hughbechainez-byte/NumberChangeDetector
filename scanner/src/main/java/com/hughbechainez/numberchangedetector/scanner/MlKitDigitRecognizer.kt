package com.hughbechainez.numberchangedetector.scanner

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

data class RecognitionBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

class MlKitDigitRecognizer : DigitRecognizer {
    private val gate = Mutex()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var closed = false
    override var inferenceCount: Int = 0
        private set

    override suspend fun recognize(bitmap: Bitmap, aggressive: Boolean): DigitRecognition = gate.withLock {
        check(!closed) { "Digit recognizer is closed" }
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)

        val raw = recognizeOnce(bitmap, "raw")
        if (raw.value != null) return@withLock adjudicate(raw, bitmap)
        if (!aggressive || raw.status == DigitRecognitionStatus.TIMEOUT || raw.status == DigitRecognitionStatus.OCR_FAILURE) {
            return@withLock raw
        }

        val threshold = binarize(bitmap)
        try {
            val enhanced = recognizeOnce(threshold, "adaptive-threshold")
            if (enhanced.value != null) adjudicate(enhanced, threshold) else betterOf(raw, enhanced)
        } finally {
            if (!threshold.isRecycled) threshold.recycle()
        }
    }

    private suspend fun recognizeOnce(bitmap: Bitmap, branch: String): DigitRecognition {
        val started = android.os.SystemClock.elapsedRealtime()
        val owned = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: return DigitRecognition(null, "", 0f, branch, DigitRecognitionStatus.INVALID_FRAME, 0L)
        val task = recognizer.process(InputImage.fromBitmap(owned, 0))
        inferenceCount++
        task.addOnCompleteListener { if (!owned.isRecycled) owned.recycle() }
        val text = try {
            withTimeout(OCR_TIMEOUT_MS) { awaitTask(task) }
        } catch (_: TimeoutCancellationException) {
            return DigitRecognition(
                null,
                "",
                0f,
                branch,
                DigitRecognitionStatus.TIMEOUT,
                android.os.SystemClock.elapsedRealtime() - started
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return DigitRecognition(
                null,
                failure.message.orEmpty(),
                0f,
                branch,
                DigitRecognitionStatus.OCR_FAILURE,
                android.os.SystemClock.elapsedRealtime() - started
            )
        }

        val parsed = parse(text)
        val status = when {
            parsed.value != null -> DigitRecognitionStatus.PARSED
            text.text.isBlank() -> DigitRecognitionStatus.NO_TEXT
            else -> DigitRecognitionStatus.NO_VALID_INTEGER
        }
        return DigitRecognition(
            value = parsed.value,
            rawText = text.text.trim(),
            confidence = parsed.confidence,
            branch = branch,
            status = status,
            elapsedMs = android.os.SystemClock.elapsedRealtime() - started,
            bounds = parsed.bounds
        )
    }

    private data class Parsed(val value: Int?, val confidence: Float, val bounds: RecognitionBounds?)

    private fun parse(text: Text): Parsed {
        val pattern = Regex("^[0-9]{1,3}$")
        val matches = ArrayList<Parsed>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                if (lineText.matches(pattern)) {
                    matches += Parsed(
                        lineText.toInt(),
                        actualConfidence(line.confidence) ?: elementConfidence(line) ?: DEFAULT_CONFIDENCE,
                        line.boundingBox?.let { RecognitionBounds(it.left, it.top, it.right, it.bottom) }
                    )
                    continue
                }
                line.elements.filter { it.text.trim().matches(pattern) }.forEach { element ->
                    matches += Parsed(
                        element.text.trim().toInt(),
                        actualConfidence(element.confidence) ?: DEFAULT_CONFIDENCE,
                        element.boundingBox?.let { RecognitionBounds(it.left, it.top, it.right, it.bottom) }
                    )
                }
            }
        }
        return matches.maxByOrNull { it.confidence } ?: Parsed(null, 0f, null)
    }

    private fun elementConfidence(line: Text.Line): Float? =
        line.elements.mapNotNull { actualConfidence(it.confidence) }.minOrNull()

    private fun actualConfidence(value: Float): Float? = value.takeIf { it.isFinite() && it in 0f..1f }

    private fun adjudicate(recognition: DigitRecognition, source: Bitmap): DigitRecognition {
        if (!shouldAdjudicateSixOrNine(recognition.value)) return recognition
        val plane = extractTopologyLuma(source, recognition.bounds)
        val topology = plane?.let { classifySixOrNine(it.values, it.width, it.height) }
        val corrected = when (topology?.decision) {
            SixNineDecision.SIX -> 6
            SixNineDecision.NINE -> 9
            else -> recognition.value
        }
        return recognition.copy(
            value = corrected,
            confidence = max(recognition.confidence, topology?.confidence ?: 0f),
            topologyDecision = topology?.decision
        )
    }

    private fun betterOf(first: DigitRecognition, second: DigitRecognition): DigitRecognition = when {
        second.value != null -> second
        first.status == DigitRecognitionStatus.NO_TEXT && second.status != DigitRecognitionStatus.NO_TEXT -> second
        else -> first
    }

    private suspend fun awaitTask(task: Task<Text>): Text = suspendCancellableCoroutine { continuation ->
        val terminal = AtomicBoolean(false)
        task.addOnSuccessListener { result ->
            if (terminal.compareAndSet(false, true) && continuation.isActive) continuation.resume(result)
        }.addOnFailureListener { failure ->
            if (terminal.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(failure)
        }.addOnCanceledListener {
            if (terminal.compareAndSet(false, true) && continuation.isActive) {
                continuation.cancel(CancellationException("ML Kit OCR task cancelled"))
            }
        }
        continuation.invokeOnCancellation { terminal.compareAndSet(false, true) }
    }

    private fun binarize(source: Bitmap): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val luma = IntArray(pixels.size)
        var total = 0L
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val value = (((pixel shr 16) and 0xff) * 30 + ((pixel shr 8) and 0xff) * 59 +
                (pixel and 0xff) * 11) / 100
            luma[index] = value
            total += value
        }
        val threshold = (total / pixels.size.coerceAtLeast(1)).toInt()
        for (index in pixels.indices) {
            pixels[index] = if (luma[index] >= threshold) 0xffffffff.toInt() else 0xff000000.toInt()
        }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        recognizer.close()
    }

    private companion object {
        const val OCR_TIMEOUT_MS = 5_000L
        const val DEFAULT_CONFIDENCE = 0.75f
    }
}

private data class LumaPlane(val values: IntArray, val width: Int, val height: Int)

private fun extractTopologyLuma(source: Bitmap, bounds: RecognitionBounds?): LumaPlane? {
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
    val base = bounds?.takeIf { it.width > 0 && it.height > 0 }
        ?: RecognitionBounds(0, 0, source.width, source.height)
    val padX = max(2, (base.width * 0.16f).toInt())
    val padY = max(2, (base.height * 0.16f).toInt())
    val left = (base.left - padX).coerceIn(0, source.width - 1)
    val top = (base.top - padY).coerceIn(0, source.height - 1)
    val right = (base.right + padX).coerceIn(left + 1, source.width)
    val bottom = (base.bottom + padY).coerceIn(top + 1, source.height)
    val width = right - left
    val height = bottom - top
    if (width < 4 || height < 6) return null
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, left, top, width, height)
    val luma = IntArray(pixels.size) { index ->
        val pixel = pixels[index]
        (((pixel shr 16) and 0xff) * 30 + ((pixel shr 8) and 0xff) * 59 + (pixel and 0xff) * 11) / 100
    }
    return LumaPlane(luma, width, height)
}
