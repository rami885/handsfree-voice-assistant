package com.handsfree.probes

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NotificationConsentTest : ProbeTest() {
    private fun sample() = StatusBarNotification("com.whatsapp", "com.whatsapp", 1, null, 10, 0, 0,
        Notification.Builder(app).setCategory(Notification.CATEGORY_CALL).setContentText("PRIVATE").build(), android.os.Process.myUserHandle(), 10)
    private fun retained(listener: ProbeNotificationListener): Int =
        (ProbeNotificationListener::class.java.getDeclaredField("notifications").apply { isAccessible = true }.get(listener) as Map<*, *>).size
    @Test fun postedNotificationsAreNotRetainedBeforeConsent() {
        val service = Robolectric.buildService(ProbeNotificationListener::class.java).create()
        try {
            controller.start("T5", ProbeConfig())
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowNotificationListenerService>(service.get()).addActiveNotification(sample())
            service.get().onListenerConnected()
            service.get().onNotificationPosted(sample())
            assertEquals(0, retained(service.get()))
        } finally { service.destroy() }
    }
    @Test fun revokingConsentClearsPreviouslyRetainedNotifications() {
        val service = Robolectric.buildService(ProbeNotificationListener::class.java).create()
        try {
            controller.setConsent(ProbeNotificationListener.ACCESS, true)
            controller.start("T5", ProbeConfig())
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowNotificationListenerService>(service.get()).addActiveNotification(sample())
            service.get().onListenerConnected()
            service.get().onNotificationPosted(sample())
            assertEquals(1, retained(service.get()))
            controller.setConsent(ProbeNotificationListener.ACCESS, false)
            assertEquals(0, retained(service.get()))
            service.get().onNotificationPosted(sample())
            assertEquals(0, retained(service.get()))
        } finally { service.destroy() }
    }
}
