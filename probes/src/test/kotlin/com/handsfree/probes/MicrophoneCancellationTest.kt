package com.handsfree.probes

import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MicrophoneCancellationTest : ProbeTest() {
    @Test fun screenOffStopsMicrophoneService() {
        // Initialize the controller and discard earlier stop requests.
        controller
        while (shadowOf(app).nextStoppedService != null) { }
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        val stopped = generateSequence { shadowOf(app).nextStoppedService }.toList()
        assertTrue(stopped.any { it.component?.className == MicrophoneService::class.java.name })
    }
}
