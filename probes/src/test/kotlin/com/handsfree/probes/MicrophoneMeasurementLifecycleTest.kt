package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MicrophoneMeasurementLifecycleTest : ProbeTest() {
    @Test fun serviceKilledBeforeWorkerStartsStillRecordsExactlyOneEnd() {
        shadowOf(app).grantPermissions(RECORD_AUDIO)
        controller.setConsent(RECORD_AUDIO, true)
        val service = Robolectric.buildService(MicrophoneService::class.java).create()
        service.get().onStartCommand(Intent().putExtra("experiment", "T3").putExtra("screenOffMeasurement", true), 0, 1)
        assertNotNull(controller.microphoneMeasurement)
        service.destroy()
        testScheduler.runCurrent()
        val ends = controller.repo.lines.value.filter { "capture_end" in it }
        assertEquals(1, ends.size)
        assertTrue(ends.single().contains("\"endReason\":\"service_killed\""))
        assertTrue(ends.single().contains("\"windowMs\":15000"))
        assertNull(controller.microphoneMeasurement)
    }
    @Test fun processDeathRecoversCheckpointWithoutInventingAnExactEndTime() {
        var now = 0L
        val capture = PcmMeasurement(15_000) { now }
        capture.add(shortArrayOf(1, 0), 2)
        now = 1_250; capture.screenOff()
        PcmCheckpoint(app).save("T3", capture)
        runOnTestDispatcher { controller.recoverMicrophoneMeasurement()?.join() }
        val events = runOnTestDispatcher { controller.repo.snapshot() }
        val end = org.json.JSONObject(events.single { it.event == "capture_end" }.value)
        assertEquals("service_killed", end.getString("endReason"))
        assertEquals("process_lost", end.getString("stopRequest"))
        assertFalse(end.getBoolean("durationExact"))
        assertTrue(end.isNull("durationMs"))
        assertEquals(1_250, end.getLong("durationLowerBoundMs"))
        assertTrue(events.any { it.event == "bucket" && org.json.JSONObject(it.value).getLong("samples") == 2L })
        assertNull(controller.recoverMicrophoneMeasurement())
    }

    @Test fun oldRunCannotClearANewerRunsCheckpoint() {
        var now = 0L
        val old = PcmCheckpoint(app)
        old.save("T3", PcmMeasurement(3_000) { now })
        now = 2_000
        val newer = PcmCheckpoint(app)
        newer.save("T3", PcmMeasurement(15_000) { now })
        old.clear()
        runOnTestDispatcher { controller.recoverMicrophoneMeasurement()?.join() }
        val end = runOnTestDispatcher { controller.repo.snapshot() }.single { it.event == "capture_end" }
        assertEquals(15_000, org.json.JSONObject(end.value).getLong("windowMs"))
    }

}
