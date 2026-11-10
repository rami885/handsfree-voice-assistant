package com.handsfree.probes

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.media.AudioDeviceInfo
import android.os.Looper
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
class BluetoothConsentTest : ProbeTest() {
    @Test fun scoRequiresIndependentConsentEvenWhenPermissionIsGranted() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            shadowOf(app).grantPermissions(BLUETOOTH_CONNECT)
            val sco = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
            val manager = shadowOf(controller.audio.manager)
            manager.setAvailableCommunicationDevices(listOf(sco))
            suspend fun attempt(): Long? = coroutineScope {
                val opened = async { controller.audio.open("T4", scoOnly = true) }
                advanceTimeBy(100); runCurrent()
                manager.callOnCommunicationDeviceChangedListeners(sco)
                shadowOf(Looper.getMainLooper()).idle(); runCurrent()
                opened.await()
            }
            assertNull(attempt())
            controller.updateConsents(setOf(BLUETOOTH_CONNECT))
            assertNotNull(attempt())
            assertTrue(controller.audio.confirmed)
            controller.updateConsents(emptySet())
            assertFalse(controller.audio.confirmed)
        } finally { controller.audio.close(); Dispatchers.resetMain() }
    }
}
