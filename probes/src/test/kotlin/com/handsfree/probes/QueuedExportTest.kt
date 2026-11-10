package com.handsfree.probes

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class QueuedExportTest : ProbeTest() {
    @Test fun exportIncludesEveryEventEnqueuedBeforeTheRequest() = runBlocking {
        val output = mutableMapOf<Uri, ByteArrayOutputStream>()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                val file = uri.buildUpon().appendPath(output.size.toString()).build()
                val stream = ByteArrayOutputStream()
                output[file] = stream
                shadowOf(app.contentResolver).registerOutputStream(file, stream)
                return file
            }
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 1
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 1
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        val payload = "a,\"b\"\nSecond line"
        repeat(1_000) { controller.repo.emit("EXPORT_QUEUE", "record", "event_$it", payload) }
        val files = ProbeExporter(app, controller.repo).export()
        assertEquals(1_000, files.rows)
        val json = JSONObject(output.getValue(files.json).toString(Charsets.UTF_8.name()))
        val rows = json.getJSONArray("events")
        assertEquals(1_000, rows.length())
        assertEquals("event_999", rows.getJSONObject(999).getString("event"))
        assertEquals(payload, rows.getJSONObject(999).getString("value"))
        val csv = output.getValue(files.csv).toString(Charsets.UTF_8.name())
        assertTrue(csv.contains("\"event_999\""))
        assertTrue(csv.contains("\"a,\"\"b\"\"\nSecond line\""))
    }
}
