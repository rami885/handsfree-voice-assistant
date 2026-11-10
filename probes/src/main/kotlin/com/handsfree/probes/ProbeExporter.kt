package com.handsfree.probes

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
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
        repo.exportMutex.withLock { write(repo.snapshot(), today()) }
    }

    internal suspend fun export(events: List<ProbeEvent>, date: LocalDate = today()): ExportedFiles = withContext(dispatcher) {
        repo.exportMutex.withLock { write(events, date) }
    }

    private fun render(events: List<ProbeEvent>): Pair<String, String> {
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
        return csv to json
    }

    /** Unique private files are completed before any URI is handed to another app. */
    internal suspend fun shareSnapshot(events: List<ProbeEvent>, date: LocalDate): ExportedFiles = withContext(dispatcher) {
        val (csv, json) = render(events)
        val directory = File(context.cacheDir, "probe-shares/${UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            val stem = dailyStem(Build.MODEL, date)
            fun write(extension: String, content: String): Uri {
                val file = File(directory, "$stem.$extension")
                file.writeText(content, Charsets.UTF_8)
                return FileProvider.getUriForFile(context, "${context.packageName}.probe-shares", file)
            }
            ExportedFiles(write("csv", csv), write("json", json), events.size)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private suspend fun write(events: List<ProbeEvent>, date: LocalDate): ExportedFiles {
        val (csv, json) = render(events)
        val created = mutableListOf<Uri>()
        val written = mutableListOf<Uri>()
        val stem = dailyStem(Build.MODEL, date)
        val resolver = context.contentResolver
        val path = "${Environment.DIRECTORY_DOWNLOADS}/probes/"
        fun write(extension: String, mime: String, content: String): Uri {
            val name = "$stem.$extension"
            val existing = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name, path, context.packageName))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }, null,
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
            if (existing != null) resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) }, null, null)
            written += uri
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
            written.forEach { uri -> context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
            return ExportedFiles(csvUri, jsonUri, events.size)
        } catch (error: Exception) {
            created.forEach { uri -> context.contentResolver.delete(uri, null, null) }
            throw error
        }
    }
}
