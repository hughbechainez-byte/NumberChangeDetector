package com.hughbechainez.numberchangedetector.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

class MediaRetrieverFrameSampler(
    context: Context,
    private val sourceUri: Uri
) : FrameSampler {
    private val enumeratedPresentationTimesMs = HashSet<Long>()
    private val appContext = context.applicationContext
    private val retriever = MediaMetadataRetriever().apply {
        setDataSource(appContext, sourceUri)
    }

    override val metadata: SourceVideoMetadata = SourceVideoMetadata(
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0,
        frameRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        } else null
    )

    override suspend fun frameAt(
        timeMs: Long,
        targetWidthPx: Int,
        exactPresentationTime: Boolean
    ): FrameSample? = withContext(Dispatchers.IO) {
        val safeTimeMs = timeMs.coerceIn(0L, metadata.durationMs)
        if (exactPresentationTime && safeTimeMs !in enumeratedPresentationTimesMs) {
            return@withContext null
        }
        val sourceWidth = metadata.encodedWidth.coerceAtLeast(1)
        val sourceHeight = metadata.encodedHeight.coerceAtLeast(1)
        val width = targetWidthPx.coerceAtLeast(64)
        val height = max(1, (sourceHeight.toFloat() * width / sourceWidth).toInt())
        val raw = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    safeTimeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    width,
                    height
                ) ?: retriever.getFrameAtTime(safeTimeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } else {
                retriever.getFrameAtTime(safeTimeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }.getOrNull() ?: return@withContext null

        val upright = rotate(raw, metadata.rotationDegrees)
        if (upright !== raw) raw.recycle()
        FrameSample(
            requestedTimeMs = safeTimeMs,
            presentationTimeMs = safeTimeMs.takeIf { exactPresentationTime },
            bitmap = upright
        )
    }

    override fun presentationTimesBetween(startMs: Long, endMs: Long): List<Long> {
        val startUs = startMs.coerceAtLeast(0L) * 1_000L
        val endUs = endMs.coerceAtLeast(startMs) * 1_000L
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(appContext, sourceUri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return emptyList()
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val result = ArrayList<Long>()
            var inspected = 0
            while (inspected < MAX_REFINEMENT_SAMPLES) {
                val sampleUs = extractor.sampleTime
                if (sampleUs < 0L || sampleUs > endUs) break
                if (sampleUs >= startUs) result += sampleUs / 1_000L
                inspected++
                if (!extractor.advance()) break
            }
            result.distinct().also { enumeratedPresentationTimesMs.addAll(it) }
        } finally {
            extractor.release()
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    override fun close() {
        retriever.release()
    }

    private companion object {
        const val MAX_REFINEMENT_SAMPLES = 200_000
    }
}
