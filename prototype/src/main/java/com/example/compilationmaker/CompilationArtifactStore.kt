package com.example.compilationmaker

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class CompilationArtifactRecord(
    val artifactId: String,
    val jobId: String,
    val batchId: String,
    val itemIndex: Int,
    val itemCount: Int,
    val sourceUri: String,
    val stagingPath: String,
    val canonicalPath: String,
    val displayName: String,
    val mimeType: String,
    val state: String = "QUEUED",
    val fileSize: Long = 0L,
    val expectedDurationMs: Long = 0L,
    val actualDurationMs: Long = 0L,
    val renderCompletedAt: Long = 0L,
    val verificationState: String = "NOT_STARTED",
    val providerUriState: String = "NOT_REQUESTED",
    val publicationState: String = "NOT_STARTED",
    val mediaStoreUri: String = "",
    val failureCategory: String = "",
    val cleanupEligibility: String = "INELIGIBLE",
    val retryCount: Int = 0,
    val sha256: String = "",
    val lastError: String = "",
    val revision: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("artifactId", artifactId); put("jobId", jobId); put("batchId", batchId)
        put("itemIndex", itemIndex); put("itemCount", itemCount); put("sourceUri", sourceUri)
        put("stagingPath", stagingPath); put("canonicalPath", canonicalPath); put("displayName", displayName)
        put("mimeType", mimeType); put("state", state); put("fileSize", fileSize)
        put("expectedDurationMs", expectedDurationMs); put("actualDurationMs", actualDurationMs)
        put("renderCompletedAt", renderCompletedAt); put("verificationState", verificationState)
        put("providerUriState", providerUriState); put("publicationState", publicationState)
        put("mediaStoreUri", mediaStoreUri); put("failureCategory", failureCategory)
        put("cleanupEligibility", cleanupEligibility); put("retryCount", retryCount)
        put("sha256", sha256); put("lastError", lastError); put("revision", revision)
        put("updatedAt", System.currentTimeMillis())
    }

    companion object {
        fun fromJson(json: JSONObject): CompilationArtifactRecord = CompilationArtifactRecord(
            artifactId = json.optString("artifactId"), jobId = json.optString("jobId"),
            batchId = json.optString("batchId"), itemIndex = json.optInt("itemIndex"),
            itemCount = json.optInt("itemCount"), sourceUri = json.optString("sourceUri"),
            stagingPath = json.optString("stagingPath"), canonicalPath = json.optString("canonicalPath"),
            displayName = json.optString("displayName"), mimeType = json.optString("mimeType", "video/mp4"),
            state = json.optString("state", "QUEUED"), fileSize = json.optLong("fileSize"),
            expectedDurationMs = json.optLong("expectedDurationMs"), actualDurationMs = json.optLong("actualDurationMs"),
            renderCompletedAt = json.optLong("renderCompletedAt"), verificationState = json.optString("verificationState"),
            providerUriState = json.optString("providerUriState"), publicationState = json.optString("publicationState"),
            mediaStoreUri = json.optString("mediaStoreUri"), failureCategory = json.optString("failureCategory"),
            cleanupEligibility = json.optString("cleanupEligibility"), retryCount = json.optInt("retryCount"),
            sha256 = json.optString("sha256"), lastError = json.optString("lastError"), revision = json.optLong("revision")
        )
    }
}

class CompilationArtifactStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "compilation_recovery").apply { mkdirs() }

    @Synchronized fun load(artifactId: String): CompilationArtifactRecord? =
        runCatching { AtomicFile(fileFor(artifactId)).readFully().toString(Charsets.UTF_8) }
            .mapCatching { CompilationArtifactRecord.fromJson(JSONObject(it)) }
            .getOrNull()

    @Synchronized fun save(record: CompilationArtifactRecord): Boolean {
        val atomic = AtomicFile(fileFor(record.artifactId))
        val stream: FileOutputStream = try { atomic.startWrite() } catch (_: Exception) { return false }
        return try {
            stream.write(record.copy(revision = record.revision + 1L).toJson().toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            atomic.failWrite(stream)
            false
        }
    }

    @Synchronized fun update(artifactId: String, transform: (CompilationArtifactRecord) -> CompilationArtifactRecord): CompilationArtifactRecord? {
        val current = load(artifactId) ?: return null
        val updated = transform(current)
        return updated.takeIf { save(it) }
    }

    private fun fileFor(artifactId: String): File = File(directory, "$artifactId.json")
}
