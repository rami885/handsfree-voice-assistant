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
class AlreadyActiveAudioRouteTest : ProbeTest() {
    @Test fun activePhoneAndScoRoutesAreReusedWithoutRequestOrCallbackWait() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val manager = shadowOf(controller.audio.manager)
        try {
            shadowOf(app).grantPermissions(BLUETOOTH_CONNECT)
            for (type in listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)) {
                manager.lockCommunicationDevice(false)
                val sco = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                controller.updateConsents(if (sco) setOf(BLUETOOTH_CONNECT) else emptySet())
                val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
                manager.setAvailableCommunicationDevices(listOf(device))
                assertTrue(controller.audio.manager.setCommunicationDevice(device))
                // Any redundant request would now be rejected; no callback is delivered.
                manager.lockCommunicationDevice(true)
                val before = currentTime
                assertEquals(0L, controller.audio.open("T4", scoOnly = sco))
                assertEquals(before, currentTime)
                assertTrue(controller.audio.confirmed)
                assertEquals(device, controller.audio.manager.communicationDevice)
                assertTrue(controller.repo.lines.value.any { "already_active" in it && "type=$type" in it })
                assertFalse(controller.repo.lines.value.any { "route_callback_timeout" in it })
                manager.lockCommunicationDevice(false)
                controller.audio.close()
            }
        } finally { manager.lockCommunicationDevice(false); controller.audio.close(); Dispatchers.resetMain() }
    }
    @Test fun anActiveScoRouteStillRequiresIndependentConsent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            shadowOf(app).grantPermissions(BLUETOOTH_CONNECT)
            val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
            shadowOf(controller.audio.manager).setAvailableCommunicationDevices(listOf(device))
            assertTrue(controller.audio.manager.setCommunicationDevice(device))
            assertNull(controller.audio.open("T4", scoOnly = true))
            assertFalse(controller.audio.confirmed)
            assertFalse(controller.repo.lines.value.any { "already_active" in it })
        } finally { controller.audio.close(); Dispatchers.resetMain() }
    }
}
