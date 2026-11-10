package com.handsfree.probes

import android.content.pm.ProviderInfo
import android.provider.MediaStore
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MeasurementExportTest : ProbeTest() {
    @Test fun structuredMeasurementsSurviveBothCsvAndJsonExports() = runTest(testDispatcher) {
        val downloads = TestDownloads().also {
            it.attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, it)
        }
        var now = 0L
        val capture = PcmMeasurement(3_000) { now }
        capture.add(shortArrayOf(1, 0), 2)
        now = 500; capture.screenOff(); now = 3_000; capture.stop("window_complete")
        capture.finish { event, fields -> controller.repo.emit("T3", "pcm", event, fields.toString()) }
        val (sco, _) = ScoMeasurement.summary(listOf(ScoAttempt(RouteOpenOutcome.SUCCESS, 10)) + List(19) { ScoAttempt(RouteOpenOutcome.TIMEOUT) })
        controller.repo.emit("T4", "sco", "summary_ms", sco.toString(), "FAIL")
        controller.repo.emit("T6", "measurement", "result", JSONObject().put("result", "timeout_no_playing").put("windowMs", IntegrationProbes.MEDIA_SEARCH_WINDOW_MS).toString(), "FAIL")
        val answer = AnswerMeasurement(this, { now }, { false }) { fields, success ->
            controller.repo.emit("T1b", "answer", "result", fields.toString(), if (success) "PASS" else "FAIL")
        }
        answer.answer(mapOf(4 to android.telephony.TelephonyManager.CALL_STATE_RINGING), { true }) { }
        now += 350
        answer.stateChanged(4, android.telephony.TelephonyManager.CALL_STATE_RINGING, android.telephony.TelephonyManager.CALL_STATE_OFFHOOK)
        controller.evaluateLockAction("send")
        val rows = controller.repo.snapshot()
        val files = ProbeExporter(app, controller.repo, dispatcher = testDispatcher).export(rows)
        val json = JSONObject(downloads.text(files.json)).getJSONArray("events")
        val csv = downloads.text(files.csv)
        assertTrue(csv.lineSequence().first().endsWith("\"measurements\""))
        // CSV has one RFC 4180 quoted JSON object in the final column for each row.
        for (index in rows.indices) {
            val fields = json.getJSONObject(index).getJSONObject("measurements")
            assertEquals(JSONObject(rows[index].value).toString(), fields.toString())
            assertTrue(csv.contains("\"" + fields.toString().replace("\"", "\"\"") + "\"\r\n"))
        }
        val measurements = (0 until json.length()).map { json.getJSONObject(it) }
        assertEquals(350, measurements.single { it.getString("experimentId") == "T1b" }.getJSONObject("measurements").getLong("latencyMs"))
        assertEquals(false, measurements.single { it.getString("experimentId") == "T1b" }.getJSONObject("measurements").get("interactive"))
        assertEquals("window_complete", measurements.single { it.getString("event") == "capture_end" }.getJSONObject("measurements").getString("endReason"))
        assertEquals(19, measurements.single { it.getString("experimentId") == "T4" }.getJSONObject("measurements").getInt("timeouts"))
        assertEquals(4_000, measurements.single { it.getString("experimentId") == "T6" }.getJSONObject("measurements").getLong("windowMs"))
        assertEquals("send", measurements.single { it.getString("experimentId") == "T10" }.getJSONObject("measurements").getString("action"))
    }
}
