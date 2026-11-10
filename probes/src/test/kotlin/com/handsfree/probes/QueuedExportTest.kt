package com.handsfree.probes

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
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
@OptIn(ExperimentalCoroutinesApi::class)
class QueuedExportTest : ProbeTest() {
    @Test fun exportIncludesEveryEventEnqueuedBeforeTheRequest() = runTest {
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
        val dispatcher = StandardTestDispatcher(testScheduler)
        val database = Room.inMemoryDatabaseBuilder(app, ProbeDatabase::class.java)
            .setQueryCoroutineContext(dispatcher).build()
        val insertStarted = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        val dao = database.events()
        val blockedDao = object : ProbeDao by dao {
            override suspend fun insert(event: ProbeEvent): Long {
                insertStarted.complete(Unit)
                releaseInsert.await()
                return dao.insert(event)
            }
        }
        val repo = ProbeRepository(app, database, blockedDao, dispatcher)
        try {
            val payload = "a,\"b\"\nSecond line"
            repo.emit("EXPORT_QUEUE", "record", "event_pending", payload)
            runCurrent()
            assertTrue("The insert must be held before export starts", insertStarted.isCompleted)
            val export = async { ProbeExporter(app, repo, dispatcher).export() }
            runCurrent()
            assertFalse("Export must wait for the pending insert", export.isCompleted)
            releaseInsert.complete(Unit)
            val files = export.await()
            assertEquals(1, files.rows)
            val json = JSONObject(output.getValue(files.json).toString(Charsets.UTF_8.name()))
            val rows = json.getJSONArray("events")
            assertEquals(1, rows.length())
            assertEquals("event_pending", rows.getJSONObject(0).getString("event"))
            assertEquals(payload, rows.getJSONObject(0).getString("value"))
            val csv = output.getValue(files.csv).toString(Charsets.UTF_8.name())
            assertTrue(csv.contains("\"event_pending\""))
            assertTrue(csv.contains("\"a,\"\"b\"\"\nSecond line\""))
        } finally {
            releaseInsert.complete(Unit)
            repo.scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }
}
