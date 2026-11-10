package com.handsfree.probes

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ReceiverProtectionTest : ProbeTest() {
    @Test fun mediaButtonReceiverIsPrivateAndStillReceivesSameAppEvents() {
        val receiver = ComponentName(app, HeadsetReceiver::class.java)
        assertFalse(app.packageManager.getReceiverInfo(receiver, 0).exported)
        controller.integrations.startButtons()
        app.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.repo.lines.value.any { "source=receiver" in it })
    }
}
