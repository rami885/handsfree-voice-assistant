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
            assertFalse(controller.repo.lines.value.any { "match_observed" in it })
            shadowOf(other).executeOnMetadataChanged(title("unrelated second callback"))
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse("Another session's callback must not complete the measurement", search.isCompleted)
            assertEquals(1, shadowOf(requested).callbacks.size)
            advanceTimeBy(250); runCurrent()
            shadowOf(requested).executeOnMetadataChanged(title("jazz requested result"))
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse("Matching metadata alone cannot complete the measurement", search.isCompleted)
            shadowOf(other).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse("Playing on another session cannot complete the measurement", search.isCompleted)
            shadowOf(requested).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            search.await()
            assertTrue(controller.repo.lines.value.any { "\"result\":\"success\"" in it })
            val measurements = controller.repo.lines.value.filter { "match_observed" in it }
            assertEquals(1, measurements.size)
            assertTrue(measurements.single().contains("\"matched\":true"))
            assertTrue(measurements.single().contains("\"title\":\"jazz requested result\""))
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
            advanceTimeBy(IntegrationProbes.MEDIA_SEARCH_WINDOW_MS); runCurrent()
            search.await()
            val result = controller.repo.lines.value.single { "\"result\":\"unsupported\"" in it }
            assertTrue(result, result.endsWith("[UNSUPPORTED]"))
            assertFalse(controller.repo.lines.value.any { "[FAIL]" in it })
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
            advanceTimeBy(IntegrationProbes.MEDIA_SEARCH_WINDOW_MS); runCurrent()
            search.await()
            val result = controller.repo.lines.value.single { "\"result\":\"timeout_no_playing\"" in it }
            assertTrue(result, result.endsWith("[FAIL]"))
            assertFalse(controller.repo.lines.value.any { "\"result\":\"unsupported\"" in it })
            assertTrue(shadowOf(player).callbacks.isEmpty())
        } finally { session.release() }
    }

    @Test fun playingWithoutMatchTimesOutAtFourSeconds() = runTest {
        val session = MediaSession(app, "wrong-title")
        try {
            val config = options()
            val player = media(session, true, "old")
            val search = async { IntegrationProbes(app, controller.repo, controller.consents) { config }.mediaSearch() }
            runCurrent()
            shadowOf(player).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(player).executeOnMetadataChanged(title("wrong title"))
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse(search.isCompleted)
            advanceTimeBy(3_999); runCurrent()
            assertFalse(search.isCompleted)
            advanceTimeBy(1); runCurrent()
            search.await()
            assertTrue(controller.repo.lines.value.any { "\"result\":\"playing_no_match\"" in it })
            assertTrue(shadowOf(player).callbacks.isEmpty())
        } finally { session.release() }
    }

    @Test fun playingBeforeMatchingTitleAlsoCompletesMeasurement() = runTest {
        val session = MediaSession(app, "playing-before-title")
        try {
            val config = options()
            val player = media(session, true, "old")
            val search = async { IntegrationProbes(app, controller.repo, controller.consents) { config }.mediaSearch() }
            runCurrent()
            shadowOf(player).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse(search.isCompleted)
            shadowOf(player).executeOnMetadataChanged(title("Jazz result"))
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            search.await()
            assertTrue(controller.repo.lines.value.any { "\"result\":\"success\"" in it })
            assertTrue(shadowOf(player).callbacks.isEmpty())
        } finally { session.release() }
    }

    @Test fun matchingArtistWithoutMatchingTitleCannotPass() = runTest {
        val session = MediaSession(app, "artist")
        try {
            val config = options().copy(query = "Miles DAVIS")
            val player = media(session, true, "old")
            val search = async { IntegrationProbes(app, controller.repo, controller.consents) { config }.mediaSearch() }
            runCurrent()
            shadowOf(player).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(player).executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "So What").putString(MediaMetadata.METADATA_KEY_ARTIST, "The Miles Davis Quintet").build())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse(search.isCompleted)
            advanceTimeBy(IntegrationProbes.MEDIA_SEARCH_WINDOW_MS); runCurrent()
            search.await()
            assertTrue(controller.repo.lines.value.any { "\"result\":\"playing_no_match\"" in it })
            assertFalse(controller.repo.lines.value.any { "\"result\":\"success\"" in it })
            assertTrue(controller.repo.lines.value.any { "The Miles Davis Quintet" in it })
        } finally { session.release() }
    }

    @Test fun absentSessionHasDistinctOutcome() = runTest {
        val config = options()
        val search = async { IntegrationProbes(app, controller.repo, controller.consents) { config }.mediaSearch() }
        runCurrent()
        advanceTimeBy(IntegrationProbes.MEDIA_SEARCH_WINDOW_MS); runCurrent()
        search.await()
        assertTrue(controller.repo.lines.value.any { "\"result\":\"no_session\"" in it })
        assertFalse(controller.repo.lines.value.any { "controller_play_from_search" in it })
    }

    @Test fun queryMatchingUsesWholeTitleTokens() {
        assertTrue(IntegrationProbes.matchesQuery("BLUE miles", "Miles in Blue"))
        assertFalse(IntegrationProbes.matchesQuery("miles davis", ""))
        assertFalse(IntegrationProbes.matchesQuery("jazz", "jazzy"))
        assertFalse(IntegrationProbes.matchesQuery("miles davis", "Miles"))
        assertFalse(IntegrationProbes.matchesQuery(" ", "anything"))
    }

    @Test fun nullMetadataInvalidatesPausedTitleMatchBeforePlaying() = runTest {
        val session = MediaSession(app, "cleared-metadata")
        try {
            val config = options()
            val player = media(session, true, "old")
            val search = async { IntegrationProbes(app, controller.repo, controller.consents) { config }.mediaSearch() }
            runCurrent()
            shadowOf(player).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0f).build())
            shadowOf(player).executeOnMetadataChanged(title("jazz result"))
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse(search.isCompleted)
            assertTrue(controller.repo.lines.value.any { "\"matched\":true" in it })
            shadowOf(player).executeOnMetadataChanged(null)
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            shadowOf(player).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse(search.isCompleted)
            advanceTimeBy(IntegrationProbes.MEDIA_SEARCH_WINDOW_MS); runCurrent(); search.await()
            val result = controller.repo.lines.value.single { "\"result\":\"playing_no_match\"" in it }
            assertTrue(result.contains("\"matched\":false"))
            assertFalse(controller.repo.lines.value.any { "\"result\":\"success\"" in it })
        } finally { session.release() }
    }

    @Test fun matchingMetadataWithoutPlayingTimesOutAndLateCallbacksCannotPass() = runTest {
        val session = MediaSession(app, "paused-match")
        try {
            val config = options()
            val player = media(session, true, "old")
            val search = async { IntegrationProbes(app, controller.repo, controller.consents) { config }.mediaSearch() }
            runCurrent()
            shadowOf(player).executeOnMetadataChanged(title("jazz result"))
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse(search.isCompleted)
            advanceTimeBy(3_999); runCurrent(); assertFalse(search.isCompleted)
            advanceTimeBy(1); runCurrent(); search.await()
            assertTrue(controller.repo.lines.value.any { "\"result\":\"timeout_no_playing\"" in it })
            shadowOf(player).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertFalse(controller.repo.lines.value.any { "\"result\":\"success\"" in it })
            assertTrue(shadowOf(player).callbacks.isEmpty())
        } finally { session.release() }
    }

}
