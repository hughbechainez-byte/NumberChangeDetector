package com.example.compilationmaker

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.UUID

/** Single authority for private compilation artifact paths. */
class CompilationStorageLayout(context: Context) {
    private val appContext = context.applicationContext

    val stagingRoot: File
        get() = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: File(appContext.filesDir, "compilation_staging"),
            "CompilationMaker/staging"
        )

    fun newArtifact(format: ExportFormat, displayName: String? = null): CompilationArtifactPaths {
        val artifactId = UUID.randomUUID().toString()
        val directory = File(stagingRoot, artifactId)
        val safeName = displayName.orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "compilation" }
        val extension = if (format == ExportFormat.Webm) ExportFormat.Mp4.extension else format.extension
        val base = "${safeName}_${artifactId.take(8)}"
        return CompilationArtifactPaths(
            artifactId = artifactId,
            directory = directory,
            partial = File(directory, "$base.partial.$extension"),
            rendered = File(directory, "$base.rendered.$extension")
        )
    }
}

data class CompilationArtifactPaths(
    val artifactId: String,
    val directory: File,
    val partial: File,
    val rendered: File
)
