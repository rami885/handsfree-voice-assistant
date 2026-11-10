package com.handsfree.probes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

data class ExportedFiles(val csv: Uri, val json: Uri, val rows: Int)
class ProbeExporter(private val context: Context, private val repo: ProbeRepository) {
    suspend fun export(): ExportedFiles = withContext(Dispatchers.IO) {
        val events = repo.db.events().all()
        val metadata = JSONObject(repo.deviceInfo())
        val columns = listOf("id", "timestampMs", "experimentId", "device", "sdkInt", "step", "event", "value", "outcome", "deviceInfo")
        fun quote(value: Any?) = "\"" + value.toString().replace("\"", "\"\"") + "\""
        val csv = buildString {
            append(columns.joinToString(",", transform = ::quote)); append("\r\n")
            events.forEach { row ->
                append(listOf(row.id, row.timestampMs, row.experimentId, row.device, row.sdkInt, row.step, row.event, row.value, row.outcome, metadata.toString()).joinToString(",", transform = ::quote)); append("\r\n")
            }
        }
        val jsonRows = JSONArray()
        events.forEach { row -> jsonRows.put(JSONObject().put("id", row.id).put("timestampMs", row.timestampMs).put("experimentId", row.experimentId)
            .put("device", row.device).put("sdkInt", row.sdkInt).put("step", row.step).put("event", row.event).put("value", row.value).put("outcome", row.outcome)) }
        val json = JSONObject().put("deviceInfo", metadata).put("events", jsonRows).toString(2)
        val created = mutableListOf<Uri>()
        val stamp = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
        fun write(extension: String, mime: String, content: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "probes-$stamp.$extension")
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/probes/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "MediaStore insert failed" }
            created += uri
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            return uri
        }
        try {
            currentCoroutineContext().ensureActive()
            val csvUri = write("csv", "text/csv", csv)
            currentCoroutineContext().ensureActive()
            val jsonUri = write("json", "application/json", json)
            currentCoroutineContext().ensureActive()
            created.forEach { uri -> context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
            ExportedFiles(csvUri, jsonUri, events.size)
        } catch (error: Exception) {
            created.forEach { uri -> context.contentResolver.delete(uri, null, null) }
            throw error
        }
    }
}
