package com.example.compilationmaker

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

data class VerifiedArtifact(
    val file: File,
    val sizeBytes: Long,
    val durationMs: Long,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val sha256: String
)

/** File-based verification; intentionally has no FileProvider or ContentResolver dependency. */
class OutputVerifier {
    fun verify(file: File, expectedDurationMs: Long? = null): VerifiedArtifact {
        require(file.exists() && file.isFile && file.canRead()) { "OutputUnreadable: ${file.absolutePath}" }
        val size = file.length()
        require(size >= 4096L) { "OutputTooSmall: $size bytes" }
        file.inputStream().use { input -> require(input.read() >= 0) { "OutputUnreadable" } }
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(duration > 0L) { "MetadataValidationFailure: no duration" }
            extractor.setDataSource(file.absolutePath)
            var video = false
            var audio = false
            for (index in 0 until extractor.trackCount) {
                when (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.substringBefore('/')) {
                    "video" -> video = true
                    "audio" -> audio = true
                }
            }
            require(video) { "TrackValidationFailure: no video track" }
            if (expectedDurationMs != null) {
                require(abs(duration - expectedDurationMs) <= 2_000L) {
                    "DurationMismatch: expected=$expectedDurationMs actual=$duration"
                }
            }
            VerifiedArtifact(file, size, duration, video, audio, sha256(file))
        } finally {
            runCatching { retriever.release() }
            runCatching { extractor.release() }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
