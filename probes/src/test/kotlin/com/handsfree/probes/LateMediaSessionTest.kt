package com.handsfree.probes

import android.app.NotificationManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.media.MediaMetadata
import android.media.session.*
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class LateMediaSessionTest : ProbeTest() {
    @Test fun coldPlayerSessionIsDiscoveredAfterTheIntentAndCallbacksAreRemoved() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val session = MediaSession(app, "cold-player")
        try {
            val pkg = "com.fixture.player"
            shadowOf(app.packageManager).addResolveInfoForIntent(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH),
                ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "Player" } })
            shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(ComponentName(app, ProbeNotificationListener::class.java), true)
            controller.setConsent(ProbeNotificationListener.ACCESS, true)
            val options = ProbeConfig(appPackage = pkg, query = "jazz")
            val integration = IntegrationProbes(app, controller.repo, controller.consents) { options }
            val search = async { integration.mediaSearch() }
            runCurrent()
            val media = MediaController(app, session.sessionToken)
            shadowOf(media).setPackageName(pkg)
            shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(media)
            shadowOf(media).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(media).executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "jazz fixture").build())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            search.await()
            assertTrue(controller.repo.lines.value.any { "\"result\":\"success\"" in it })
            assertTrue(shadowOf(media).callbacks.isEmpty())
        } finally { session.release(); Dispatchers.resetMain() }
    }
    @Test fun coldSessionWithAlreadyPublishedMatchingMetadataAndPlayingStateSucceeds() = runTest {
        val session = MediaSession(app, "cold-complete-player")
        try {
            val pkg = "com.fixture.player"
            shadowOf(app.packageManager).addResolveInfoForIntent(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH),
                ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "Player" } })
            shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(ComponentName(app, ProbeNotificationListener::class.java), true)
            controller.setConsent(ProbeNotificationListener.ACCESS, true)
            val options = ProbeConfig(appPackage = pkg, query = "jazz")
            val search = async { IntegrationProbes(app, controller.repo, controller.consents) { options }.mediaSearch() }
            runCurrent()
            val media = MediaController(app, session.sessionToken)
            shadowOf(media).setPackageName(pkg)
            shadowOf(media).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Jazz fixture").build())
            shadowOf(media).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(media)
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            search.await()
            assertTrue(controller.repo.lines.value.any { "\"result\":\"success\"" in it })
            assertTrue(shadowOf(media).callbacks.isEmpty())
        } finally { session.release() }
    }

}
