package com.example.compilationmaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CompilationBatchItem(
    val uri: String,
    val label: String,
    val state: String = "queued",
    val elapsedMs: Long = 0L,
    val outputPath: String = "",
    val error: String = ""
)

data class CompilationBatchRecord(
    val batchId: String,
    val workId: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val items: List<CompilationBatchItem>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("batchId", batchId)
        put("workId", workId)
        put("startedAtMs", startedAtMs)
        put("updatedAtMs", updatedAtMs)
        put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): CompilationBatchRecord {
            val items = mutableListOf<CompilationBatchItem>()
            val array = json.optJSONArray("items") ?: JSONArray()
            for (index in 0 until array.length()) {
                items += batchItemFromJson(array.optJSONObject(index) ?: continue)
            }
            return CompilationBatchRecord(
                batchId = json.optString("batchId"),
                workId = json.optString("workId"),
                startedAtMs = json.optLong("startedAtMs"),
                updatedAtMs = json.optLong("updatedAtMs"),
                items = items
            )
        }
    }
}

private fun CompilationBatchItem.toJson(): JSONObject = JSONObject().apply {
    put("uri", uri)
    put("label", label)
    put("state", state)
    put("elapsedMs", elapsedMs)
    put("outputPath", outputPath)
    put("error", error)
}

private fun batchItemFromJson(json: JSONObject): CompilationBatchItem = CompilationBatchItem(
    uri = json.optString("uri"),
    label = json.optString("label"),
    state = json.optString("state", "queued"),
    elapsedMs = json.optLong("elapsedMs"),
    outputPath = json.optString("outputPath"),
    error = json.optString("error")
)

class CompilationBatchStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("compilation_batch", Context.MODE_PRIVATE)
    private val key = "record"

    @Synchronized fun load(): CompilationBatchRecord? = preferences.getString(key, null)?.let {
        runCatching { CompilationBatchRecord.fromJson(JSONObject(it)) }.getOrNull()
    }

    @Synchronized fun save(record: CompilationBatchRecord): Boolean = preferences.edit()
        .putString(key, record.toJson().toString()).commit()

    @Synchronized fun update(transform: (CompilationBatchRecord) -> CompilationBatchRecord): CompilationBatchRecord? {
        val current = load() ?: return null
        val updated = transform(current).copy(updatedAtMs = System.currentTimeMillis())
        save(updated)
        return updated
    }
}
