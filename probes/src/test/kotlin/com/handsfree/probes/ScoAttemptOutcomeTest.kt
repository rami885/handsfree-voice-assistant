package com.handsfree.probes

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.media.AudioDeviceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class ScoAttemptOutcomeTest : ProbeTest() {
    @Test fun callbackTimeoutAndRouteFailureAreCountedSeparately() = runTest(testDispatcher) {
        shadowOf(app).grantPermissions(BLUETOOTH_CONNECT)
        controller.setConsent(BLUETOOTH_CONNECT, true)
        val manager = shadowOf(controller.audio.manager)
        val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        manager.setAvailableCommunicationDevices(listOf(device))
        val open = async { controller.audio.open("T4", scoOnly = true) }
        runCurrent()
        advanceTimeBy(2_100); runCurrent()
        assertNull(open.await())
        assertEquals(RouteOpenOutcome.TIMEOUT, controller.audio.lastOpenOutcome)
        manager.setAvailableCommunicationDevices(emptyList())
        assertNull(controller.audio.open("T4", scoOnly = true))
        assertEquals(RouteOpenOutcome.FAILURE, controller.audio.lastOpenOutcome)
    }

    @Test fun twentyFailedOpensStillProduceASummaryAndFailVerdict() {
        shadowOf(app).grantPermissions(BLUETOOTH_CONNECT)
        controller.setConsent(BLUETOOTH_CONNECT, true)
        shadowOf(controller.audio.manager).setAvailableCommunicationDevices(emptyList())
        controller.start("T4", ProbeConfig())
        testScheduler.advanceTimeBy(3_000); testScheduler.runCurrent()
        val events = runOnTestDispatcher { controller.repo.snapshot() }
        assertEquals(20, events.count { it.experimentId == "T4" && it.event == "attempt" })
        val summary = events.single { it.experimentId == "T4" && it.event == "summary_ms" }
        assertEquals("FAIL", summary.outcome)
        val fields = org.json.JSONObject(summary.value)
        assertEquals(20, fields.getInt("failures")); assertEquals(0, fields.getInt("timeouts"))
        assertEquals(0, fields.getInt("successes")); assertEquals(0.0, fields.getDouble("successRate"), 0.0)
    }
}
