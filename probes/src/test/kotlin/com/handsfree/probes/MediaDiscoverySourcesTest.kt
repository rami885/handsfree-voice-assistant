package com.handsfree.probes

import android.app.Notification
import android.app.NotificationManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.media.MediaMetadata
import android.media.session.*
import android.os.Looper
import android.provider.MediaStore
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class MediaDiscoverySourcesTest : ProbeTest() {
    @Test fun bothDiscoveryMethodsAreRecordedWithoutDuplicatingCallbacks() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val session = MediaSession(app, "two-sources")
        val secondSession = MediaSession(app, "notification-only")
        val listener = Robolectric.buildService(ProbeNotificationListener::class.java).create()
        try {
            val pkg = app.packageName
            shadowOf(app.packageManager).addResolveInfoForIntent(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH),
                ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "Player" } })
            shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(ComponentName(app, ProbeNotificationListener::class.java), true)
            val media = MediaController(app, session.sessionToken)
            shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(media)
            listener.get().onListenerConnected()
            controller.setConsent(ProbeNotificationListener.ACCESS, true)
            controller.start("T6", ProbeConfig(appPackage = pkg, query = "jazz"))
            runCurrent()
            val notification = Notification.Builder(app).build().apply { extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, session.sessionToken) }
            listener.get().onNotificationPosted(StatusBarNotification(pkg, pkg, 1, null, 10, 0, 0, notification, android.os.Process.myUserHandle(), 10))
            val secondNotification = Notification.Builder(app).build().apply { extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, secondSession.sessionToken) }
            listener.get().onNotificationPosted(StatusBarNotification(pkg, pkg, 2, null, 10, 0, 0, secondNotification, android.os.Process.myUserHandle(), 10))
            advanceTimeBy(100); runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            val lines = controller.repo.lines.value
            assertTrue(lines.any { "source=active_sessions" in it })
            assertEquals(2, lines.count { "source=notification_token" in it })
            assertEquals(1, shadowOf(media).callbacks.size)
            shadowOf(media).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(media).executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "jazz fixture").build())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertTrue(shadowOf(media).callbacks.isEmpty())
        } finally { controller.stop("test"); listener.destroy(); session.release(); secondSession.release(); Dispatchers.resetMain() }
    }
}
