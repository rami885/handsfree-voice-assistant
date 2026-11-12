package com.handsfree.probes

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class LockReadSummaryTest : ProbeTest() {
    @Test fun latestSummaryRequiresAccessConsentAndPersonalResultsWhenLocked() {
        controller.setConsent(ProbeNotificationListener.ACCESS, true)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, ProbeNotificationListener::class.java), true)
        controller.start("T10", ProbeConfig())
        val listener = Robolectric.buildService(ProbeNotificationListener::class.java).create()
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        try {
            listener.get().onListenerConnected()
            fun post(time: Long, summary: String) {
                val notification = Notification.Builder(app).setContentTitle("Test notification").setContentText(summary).build()
                listener.get().onNotificationPosted(StatusBarNotification("com.fixture.messages", "com.fixture.messages",
                    time.toInt(), null, 10, 0, 0, notification, android.os.Process.myUserHandle(), time))
            }
            post(10, "older summary"); post(20, "PRIVATE latest summary")
            keyguard.setKeyguardLocked(true)
            assertNull(listener.get().lastNotificationSummary())
            controller.setConsent("personal_results_on_lock", true)
            assertEquals("Test notification. PRIVATE latest summary", listener.get().lastNotificationSummary())
            controller.setConsent("personal_results_on_lock", false)
            assertNull(listener.get().lastNotificationSummary())
            // Re-populate after revocation stopped the run and cleared notification snapshots.
            post(20, "PRIVATE latest summary")
            keyguard.setKeyguardLocked(false)
            assertEquals("Test notification. PRIVATE latest summary", listener.get().lastNotificationSummary())
            controller.setConsent(ProbeNotificationListener.ACCESS, false)
            assertNull(listener.get().lastNotificationSummary())
            assertFalse(controller.repo.lines.value.any { "PRIVATE" in it })
        } finally { listener.destroy() }
    }
}
