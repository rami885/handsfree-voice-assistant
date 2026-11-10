package com.handsfree.probes

import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExportTest {
    @Test fun exportsQueuedRecordsToCsvAndJson() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as ProbesApp).controller.repo
        val marker = "EXPORT_FIXTURE_${java.util.UUID.randomUUID()}"
        val payload = "a,\"b\"\nSecond line"
        val before = System.currentTimeMillis()
        repo.emit(marker, "seed", "csv_json", payload, "FIXTURE")
        repo.emit(marker, "seed", "second", "plain", "FIXTURE")
        // Keep the export mutex while reading: a later export can retire these URIs.
        withTimeout(30_000) {
            while (true) {
                val files = ProbeExporter(context, repo).export()
                val verified = repo.exportMutex.withLock {
                    val present = listOf(files.csv, files.json).all { uri ->
                        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads._ID), null, null, null)?.use { it.moveToFirst() } == true
                    }
                    // A queued auto-export may have completed before we acquired the mutex.
                    if (!present) return@withLock false
                    assertExport(context, files, marker, payload, before)
                    true
                }
                if (verified) break
            }
        }
    }

    private fun assertExport(context: android.content.Context, files: ExportedFiles, marker: String, payload: String, before: Long) {
        fun read(uri: android.net.Uri): String = checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
        val csv = read(files.csv)
        assertTrue(csv.startsWith("\"id\",\"timestampMs\",\"experimentId\",\"device\",\"sdkInt\""))
        assertTrue(csv.contains("\"a,\"\"b\"\"\nSecond line\""))
        assertTrue(csv.contains(marker))
        val json = JSONObject(read(files.json))
        assertTrue(json.getJSONObject("deviceInfo").has("probesVersion"))
        val events = json.getJSONArray("events")
        assertEquals(files.rows, events.length())
        val seeded = (0 until events.length()).map(events::getJSONObject).filter { it.getString("experimentId") == marker }
        assertEquals(2, seeded.size)
        assertEquals(payload, seeded[0].getString("value"))
        assertEquals(android.os.Build.VERSION.SDK_INT, seeded[0].getInt("sdkInt"))
        assertTrue(seeded[0].getLong("timestampMs") in before..System.currentTimeMillis())
        assertEquals("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", seeded[0].getString("device"))
        listOf(files.csv, files.json).forEach { uri ->
            context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(0).endsWith("/probes/"))
                assertEquals(0, it.getInt(1))
            }
        }
    }
}
