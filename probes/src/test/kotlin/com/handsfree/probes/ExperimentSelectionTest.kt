package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.ProviderInfo
import android.provider.MediaStore
import org.robolectric.shadows.ShadowContentResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ExperimentSelectionTest : ProbeTest() {
    @Test fun selectionDoesNotStartARunAndRecordsUnderNewId() = runOnTestDispatcher {
        controller.select("T7")
        testScheduler.advanceUntilIdle()
        assertEquals("T7", controller.currentId)
        val rows = controller.repo.snapshot()
        assertTrue(rows.any { it.experimentId == "T7" && it.step == "screen" && it.event == "selected" })
        assertFalse(rows.any { it.step == "start" || it.event == "show_requested" })
        assertFalse(rows.any { it.step == "interruption" || it.event == "auto_export" })
        assertNull(shadowOf(app).nextStoppedService)
        assertFalse(controller.screeningEnabled)
        assertFalse(controller.speech.isListening)
    }

    @Test fun switchingIdleSelectionsDoesNotStopOrExport() = runOnTestDispatcher {
        controller.select("T2")
        controller.select("T10")
        testScheduler.advanceUntilIdle()
        val rows = controller.repo.snapshot()
        assertFalse(rows.any { it.step == "interruption" || it.event == "auto_export" })
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test fun freshControllerRestoresSelectionWithoutStartingOrInterrupting() = runOnTestDispatcher {
        controller.select("T7")
        app.controller = newController(newRepository())
        assertEquals("T7", controller.currentId)
        controller.select("T10")
        testScheduler.advanceUntilIdle()
        assertFalse(controller.repo.snapshot().any { it.step in setOf("start", "interruption") || it.event == "auto_export" })
    }

    @Test fun completedRunDoesNotCauseAnInterruptionOnSelection() = runOnTestDispatcher {
        controller.start("T12", ProbeConfig())
        testScheduler.advanceUntilIdle()
        val before = controller.repo.snapshot().size
        controller.select("T7")
        testScheduler.advanceUntilIdle()
        val added = controller.repo.snapshot().drop(before)
        assertFalse(added.any { it.step == "interruption" || it.event == "auto_export" })
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test fun switchCancelsAnActiveRunUnderOldIdAndKeepsAutoExport() = runOnTestDispatcher {
        val downloads = TestDownloads().also {
            it.attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, it)
        }
        shadowOf(app).grantPermissions(RECORD_AUDIO)
        controller.setConsent(RECORD_AUDIO, true)
        controller.start("T3", ProbeConfig())
        testScheduler.runCurrent() // Suspend the run in the five-second pre-capture window.
        controller.select("T7")
        testScheduler.advanceUntilIdle()
        val rows = controller.repo.snapshot()
        assertTrue(rows.any { it.experimentId == "T3" && it.step == "interruption" && "reason=screen_changed;" in it.value })
        assertTrue(rows.any { it.experimentId == "T3" && it.step == "run" && it.event == "cancelled" })
        assertTrue(rows.any { it.experimentId == "T3" && it.step == "export" && "reason=interrupt:screen_changed;" in it.value && it.outcome == "SUCCESS" })
        assertTrue(rows.any { it.experimentId == "T7" && it.step == "screen" && it.event == "selected" })
        assertFalse(rows.any { it.experimentId == "T7" && it.step in setOf("interruption", "run", "start", "pcm") })
        assertTrue(downloads.exportedEvents().any { it.getString("experimentId") == "T3" && it.getString("event") == "cancelled" })
        assertTrue(downloads.exportedEvents().any { it.getString("experimentId") == "T7" && it.getString("event") == "selected" })
        assertFalse(generateSequence { shadowOf(app).nextStartedService }.any { it.component?.className == MicrophoneService::class.java.name })
    }

    @Test fun selectionRefreshesNotificationsAndResetsSpeechExperimentState() {
        val service = Robolectric.buildService(ProbeNotificationListener::class.java).create()
        try {
            controller.setConsent(ProbeNotificationListener.ACCESS, true)
            service.get().onListenerConnected()
            controller.speech.experimentChanged("T1a")
            val emptyWindows = SpeechEngine::class.java.getDeclaredField("emptyWindows").apply { isAccessible = true }
            emptyWindows.setInt(controller.speech, 2)
            controller.select("T5")
            assertEquals(0, emptyWindows.getInt(controller.speech))
            assertTrue(controller.repo.lines.value.any { it.startsWith("T5 / notification / existing_visible_not_read:") })
            val count = controller.repo.lines.value.count { it.startsWith("T5 / notification / existing_visible_not_read:") }
            controller.select("T5")
            assertEquals(count + 1, controller.repo.lines.value.count { it.startsWith("T5 / notification / existing_visible_not_read:") })
        } finally { service.destroy() }
    }

    @Test fun returningToListKeepsSelectionAndSameScreenKeepsRun() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup()
        try {
            activity.get().show("T7")
            controller.start("T7", ProbeConfig(query = "test query"))
            val before = controller.repo.lines.value.count { "interruption" in it }
            activity.get().show("T7")
            assertEquals("test query", controller.config.query)
            assertEquals(before, controller.repo.lines.value.count { "interruption" in it })
            activity.get().show(null)
            assertEquals("T7", controller.currentId)
            assertEquals(before, controller.repo.lines.value.count { "interruption" in it })
        } finally { activity.pause().stop().destroy() }
    }
}
