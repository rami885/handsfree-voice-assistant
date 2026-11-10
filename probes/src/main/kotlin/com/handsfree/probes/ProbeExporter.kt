package com.handsfree.probes

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class ExportedFiles(val csv: Uri, val json: Uri, val rows: Int)
class ProbeExporter(
    private val context: Context,
    private val repo: ProbeRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    companion object {
        fun dailyStem(model: String, date: LocalDate): String {
            val safeModel = model.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
                .ifBlank { "device" }
            return "probes-$safeModel-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
        }
    }
    suspend fun export(): ExportedFiles = withContext(dispatcher) {
        repo.exportMutex.withLock { write(repo.snapshot()) }
    }

    internal suspend fun export(events: List<ProbeEvent>): ExportedFiles = withContext(dispatcher) {
        repo.exportMutex.withLock { write(events) }
    }

    private suspend fun write(events: List<ProbeEvent>): ExportedFiles {
        val metadata = JSONObject(repo.deviceInfo())
        val columns = listOf("id", "timestampMs", "experimentId", "device", "sdkInt", "step", "event", "value", "outcome", "deviceInfo")
        fun quote(value: Any?): String {
            val text = value.toString()
            val safe = if (text.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$text" else text
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
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
        val stem = dailyStem(Build.MODEL, today())
        val resolver = context.contentResolver
        val path = "${Environment.DIRECTORY_DOWNLOADS}/probes/"
        fun write(extension: String, mime: String, content: String): Uri {
            val name = "$stem.$extension"
            val existing = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
                arrayOf(name, path, context.packageName), null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)) else null
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, path)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = existing ?: checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "MediaStore insert failed" }.also { created += it }
            // Truncate the old content; a shorter snapshot must not leave trailing bytes.
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            return uri
        }
        try {
            currentCoroutineContext().ensureActive()
            val csvUri = write("csv", "text/csv", csv)
            currentCoroutineContext().ensureActive()
            val jsonUri = write("json", "application/json", json)
            currentCoroutineContext().ensureActive()
            created.forEach { uri -> context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
            return ExportedFiles(csvUri, jsonUri, events.size)
        } catch (error: Exception) {
            created.forEach { uri -> context.contentResolver.delete(uri, null, null) }
            throw error
        }
    }
}
