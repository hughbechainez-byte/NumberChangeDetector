package com.example.compilationmaker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

class CompilationPublisher(context: Context) {
    private val appContext = context.applicationContext

    fun publish(record: CompilationArtifactRecord, source: File): Uri {
        require(source.exists() && source.isFile) { "Publication source is missing" }
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${record.displayName.ifBlank { "CompilationMaker" }}_${record.artifactId.take(8)}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, record.mimeType.ifBlank { "video/mp4" })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CompilationMaker")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        var finalized = false
        try {
            resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                FileInputStream(source).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                        input.copyTo(output, 1024 * 1024)
                        output.flush()
                        output.fd.sync()
                    }
                }
            } ?: error("MediaStore write failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                require(resolver.update(uri, finalValues, null, null) == 1) { "MediaStore finalize failed" }
            }
            val actualSize = resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                }
                total
            } ?: -1L
            require(actualSize == source.length()) { "PublicationVerificationFailure: size $actualSize != ${source.length()}" }
            finalized = true
            return uri
        } finally {
            if (!finalized) runCatching { resolver.delete(uri, null, null) }
        }
    }
}
