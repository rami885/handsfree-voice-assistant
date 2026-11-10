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
class AudioRoutingTest : ProbeTest() {
    @Test fun phoneAndWiredRoutesWorkWithoutBluetoothPermission() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            shadowOf(app).denyPermissions(BLUETOOTH_CONNECT)
            for (type in listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_WIRED_HEADSET)) {
                val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
                val manager = shadowOf(controller.audio.manager)
                manager.setAvailableCommunicationDevices(listOf(device))
                val opened = async { controller.audio.open("T2") }
                advanceTimeBy(100); runCurrent()
                manager.callOnCommunicationDeviceChangedListeners(device)
                shadowOf(Looper.getMainLooper()).idle(); runCurrent()
                assertNotNull(opened.await())
                assertEquals(type, controller.audio.manager.communicationDevice?.type)
                controller.audio.close()
            }
        } finally { controller.audio.close(); Dispatchers.resetMain() }
    }
}
