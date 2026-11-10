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
class ControllerMediaSearchTest : ProbeTest() {
    private val pkg = "com.fixture.player"
    private fun options(): ProbeConfig {
        controller.setConsent(ProbeNotificationListener.ACCESS, true)
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "Player" } })
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(ComponentName(app, ProbeNotificationListener::class.java), true)
        return ProbeConfig(appPackage = pkg, query = "jazz", mediaMethod = "controller")
    }
    private fun title(value: String) = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, value).build()
    private fun media(session: MediaSession, supported: Boolean, baseline: String): MediaController =
        MediaController(app, session.sessionToken).also {
            shadowOf(it).setPackageName(pkg)
            shadowOf(it).setPlaybackState(PlaybackState.Builder().setActions(if (supported) PlaybackState.ACTION_PLAY_FROM_SEARCH else 0L).build())
            shadowOf(it).setMetadata(title(baseline))
            shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(it)
        }

    @Test fun onlyRequestedSessionCallbacksCanCompleteMeasurementAndBaselinesCannot() = runTest {
        val first = MediaSession(app, "requested-player")
        val second = MediaSession(app, "other-player")
        try {
            val config = options()
            val requested = media(first, true, "old requested title")
            val other = media(second, true, "stale second title")
            val integration = IntegrationProbes(app, controller.repo, controller.consents) { config }
            val search = async { integration.mediaSearch() }
            runCurrent()
            assertFalse("Discovery baselines must not complete the measurement", search.isCompleted)
            assertEquals(1, controller.repo.lines.value.count { "controller_play_from_search" in it })
            assertEquals(2, controller.repo.lines.value.count { "baseline_at_discovery" in it })
            assertFalse(controller.repo.lines.value.any { "first_after_request" in it })
            shadowOf(other).executeOnMetadataChanged(title("unrelated second callback"))
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse("Another session's callback must not complete the measurement", search.isCompleted)
            assertEquals(1, shadowOf(requested).callbacks.size)
            advanceTimeBy(250); runCurrent()
            shadowOf(requested).executeOnMetadataChanged(title("jazz requested result"))
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            search.await()
            val measurements = controller.repo.lines.value.filter { "first_after_request" in it }
            assertEquals(1, measurements.size)
            assertTrue(measurements.single().contains("title=jazz requested result"))
            assertTrue(shadowOf(requested).callbacks.isEmpty())
            assertTrue(shadowOf(other).callbacks.isEmpty())
        } finally { first.release(); second.release() }
    }

    @Test fun noAdvertisedPlayFromSearchIsUnsupportedWithoutSendingARequest() = runTest {
        val session = MediaSession(app, "unsupported-player")
        try {
            val config = options()
            val player = media(session, false, "pre-existing title")
            val integration = IntegrationProbes(app, controller.repo, controller.consents) { config }
            val search = async { integration.mediaSearch() }
            runCurrent()
            assertFalse(search.isCompleted)
            assertFalse(controller.repo.lines.value.any { "controller_play_from_search" in it })
            advanceTimeBy(20_000); runCurrent()
            search.await()
            val result = controller.repo.lines.value.single { "play_from_search_not_advertised" in it }
            assertTrue(result, result.endsWith("[UNSUPPORTED]"))
            assertFalse(controller.repo.lines.value.any { "[FAILED]" in it })
            assertTrue(shadowOf(player).callbacks.isEmpty())
        } finally { session.release() }
    }

    @Test fun timeoutAfterControllerRequestRemainsFailed() = runTest {
        val session = MediaSession(app, "silent-player")
        try {
            val config = options()
            val player = media(session, true, "pre-existing title")
            val integration = IntegrationProbes(app, controller.repo, controller.consents) { config }
            val search = async { integration.mediaSearch() }
            runCurrent()
            assertEquals(1, controller.repo.lines.value.count { "controller_play_from_search" in it })
            assertFalse(search.isCompleted)
            advanceTimeBy(20_000); runCurrent()
            search.await()
            val result = controller.repo.lines.value.single { "timeout_20_seconds" in it }
            assertTrue(result, result.endsWith("[FAILED]"))
            assertFalse(controller.repo.lines.value.any { "play_from_search_not_advertised" in it })
            assertTrue(shadowOf(player).callbacks.isEmpty())
        } finally { session.release() }
    }

}
