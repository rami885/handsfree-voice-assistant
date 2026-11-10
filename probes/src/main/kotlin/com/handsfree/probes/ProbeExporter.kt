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

    private val path = "${Environment.DIRECTORY_DOWNLOADS}/probes/"
    private data class DailyItem(val uri: Uri, val name: String, val pending: Boolean)

    // Staging names are unique, so MediaStore need not rename a colliding daily filename.
    private fun dailyName(name: String): String? {
        val match = Regex("^(probes-[a-z0-9._-]+-[0-9]{8})(?:-pending-[a-f0-9-]+)?\\.(csv|json)$").matchEntire(name)
            ?: return null
        return "${match.groupValues[1]}.${match.groupValues[2]}"
    }

    private fun ownedItems(): List<DailyItem> {
        val items = mutableListOf<DailyItem>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.IS_PENDING),
            Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(path, context.packageName))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = dailyName(cursor.getString(1)) ?: continue
                items += DailyItem(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)), name, cursor.getInt(2) == 1)
            }
        }
        return items
    }

    /** Pending content may be incomplete, including legacy in-place writes. Never publish it. */
    internal suspend fun recoverPending() = withContext(dispatcher) {
        repo.exportMutex.withLock { recoverPendingLocked() }
    }

    private fun recoverPendingLocked() {
        ownedItems().filter { it.pending }.forEach { item ->
            check(context.contentResolver.delete(item.uri, null, null) == 1) { "Pending export cleanup failed" }
        }
    }

    private suspend fun write(events: List<ProbeEvent>, date: LocalDate): ExportedFiles {
        recoverPendingLocked()
        val (csv, json) = render(events)
        val created = mutableListOf<Uri>()
        val stem = dailyStem(Build.MODEL, date)
        val resolver = context.contentResolver
        val previous = ownedItems().filter { it.name == "$stem.csv" || it.name == "$stem.json" }
        var published = false
        fun write(extension: String, mime: String, content: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$stem-pending-${UUID.randomUUID()}.$extension")
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, path)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "MediaStore insert failed" }
            created += uri
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            return uri
        }
        try {
            currentCoroutineContext().ensureActive()
            val csvUri = write("csv", "text/csv", csv)
            currentCoroutineContext().ensureActive()
            val jsonUri = write("json", "application/json", json)
            currentCoroutineContext().ensureActive()
            // Once both streams are complete, cancellation cannot interrupt publication/retirement.
            withContext(NonCancellable) {
                created.forEach { uri ->
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "Export publication failed" }
                }
                published = true
                previous.forEach { check(resolver.delete(it.uri, null, null) == 1) { "Previous export cleanup failed" } }
                listOf(csvUri to "csv", jsonUri to "json").forEach { (uri, extension) ->
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "$stem.$extension") }, null, null) == 1) { "Export rename failed" }
                }
            }
            return ExportedFiles(csvUri, jsonUri, events.size)
        } catch (error: Exception) {
            // Before publication, keep the old complete pair. After publication, keep the new
            // complete pair even if retiring an old item fails; a later export removes duplicates.
            if (!published) withContext(NonCancellable) {
                created.forEach { uri ->
                    try { check(resolver.delete(uri, null, null) == 1) { "Incomplete export cleanup failed" } }
                    catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                }
            }
            throw error
        }
    }
}
