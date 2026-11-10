package com.handsfree.probes

import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExportTest {
    @Test fun exportsSeededRecordsToCsvAndJson() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as ProbesApp).controller.repo
        val marker = "EXPORT_FIXTURE_${java.util.UUID.randomUUID()}"
        val payload = "a,\"b\"\nSecond line"
        repo.db.events().insert(ProbeEvent(timestampMs = 123456789, experimentId = marker, device = "fixture-model", sdkInt = 31,
            step = "seed", event = "csv_json", value = payload, outcome = "FIXTURE"))
        repo.db.events().insert(ProbeEvent(timestampMs = 123456790, experimentId = marker, device = "fixture-model", sdkInt = 31,
            step = "seed", event = "second", value = "plain", outcome = "FIXTURE"))
        val files = ProbeExporter(context, repo).export()
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
        assertEquals(31, seeded[0].getInt("sdkInt"))
        assertEquals(123456789L, seeded[0].getLong("timestampMs"))
        assertEquals("fixture-model", seeded[0].getString("device"))
        listOf(files.csv, files.json).forEach { uri ->
            context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(0).endsWith("/probes/"))
                assertEquals(0, it.getInt(1))
            }
        }
    }
}
