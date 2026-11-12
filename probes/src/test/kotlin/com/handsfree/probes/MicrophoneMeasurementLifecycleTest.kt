package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MicrophoneMeasurementLifecycleTest : ProbeTest() {
    @Test fun destructionAfterSwitchExportsTheExperimentThatStartedTheService() = runOnTestDispatcher {
        val downloads = TestDownloads().also {
            it.attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, it)
        }
        shadowOf(app).grantPermissions(RECORD_AUDIO)
        controller.consents.set(RECORD_AUDIO, true)
        controller.start("T3", ProbeConfig())
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        val intent = checkNotNull(shadowOf(app).nextStartedService)
        assertEquals(MicrophoneService::class.java.name, intent.component?.className)
        assertEquals("T3", intent.getStringExtra("experiment"))
        val service = Robolectric.buildService(MicrophoneService::class.java).create()
        service.get().onStartCommand(intent, 0, 1)
        assertNotNull(controller.microphoneMeasurement)
        assertTrue(controller.repo.lines.value.any { it.startsWith("T3 / fgs / started:") })
        testScheduler.runCurrent() // Start the PCM worker as well as the foreground service.
        assertNotNull(controller.microphoneMeasurement)
        // stopService and onDestroy are separate callbacks; selection changes between them.
        controller.select("T7")
        assertEquals("T7", controller.currentId)
        assertNotNull(shadowOf(app).nextStoppedService)
        service.destroy()
        testScheduler.advanceUntilIdle()
        val rows = controller.repo.snapshot()
        val microphoneExports = rows.filter { it.event == "auto_export" && "reason=microphone_" in it.value }
        assertTrue(microphoneExports.any { "reason=microphone_destroyed;" in it.value })
        assertTrue(microphoneExports.any { "reason=microphone_end;" in it.value })
        assertTrue(microphoneExports.all { it.experimentId == "T3" && it.outcome == "SUCCESS" })
        val ends = rows.filter { it.event == "capture_end" }
        assertEquals(1, ends.size)
        assertEquals("T3", ends.single().experimentId)
        assertFalse(rows.any { it.experimentId == "T7" && (it.step in setOf("pcm", "fgs") || "reason=microphone_" in it.value) })
        assertTrue(downloads.exportedEvents().any { it.getString("experimentId") == "T3" &&
            it.getString("event") == "auto_export" && "reason=microphone_destroyed;" in it.getString("value") })
    }

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
