package com.handsfree.probes

import android.app.role.RoleManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [QueuedSessionRequestShadow::class, QueuedSessionShowShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class PendingSessionShowTest : ProbeTest() {
    @Test fun staleActionSessionKeepsTheNewerQueuedShow() = rejectStaleShow("T2", "T10")

    @Test fun staleFocusSessionKeepsTheNewerQueuedShow() = rejectStaleShow("T7", "T10")

    @Test fun staleGenerationKeepsTheNewerQueuedShowForTheSameExperiment() = rejectStaleShow("T2", "T2")

    private fun rejectStaleShow(previous: String, latest: String) {
        shadowOf(app.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_ASSISTANT)
        val service = Robolectric.buildService(ProbeVoiceService::class.java).create()
        val session = ProbeSession(app)
        try {
            PendingSessionShows.reset()
            service.get().onReady()
            controller.select(previous)
            controller.showSession(previous)
            controller.select(latest)
            controller.showSession(latest)
            assertEquals(2, PendingSessionShows.requests.size)
            val root = session.onCreateContentView()

            assertTrue(PendingSessionShows.deliverNext(session))
            // hide() removes queued MSG_SHOW, so the newer callback must still be queued.
            assertEquals(1, PendingSessionShows.requests.size)
            assertEquals(0, PendingSessionShows.hides)
            assertNull(controller.session)
            assertNull(session.experimentId)
            assertNull(session.requestGeneration)
            assertEquals(listOf("Stop"), buttons(root).map { it.text.toString() })
            buttons(root).single().performClick()
            controller.launchFromSession("timer", session)
            session.requestFocus(previous)
            testScheduler.runCurrent()
            assertFalse(controller.repo.lines.value.any {
                " / focus / " in it || " / session_voice / " in it ||
                    " / interruption / " in it || " / auto_export:" in it
            })

            // Deliver from the queue rather than calling the new onShow directly.
            assertTrue(PendingSessionShows.deliverNext(session))
            assertSame(session, controller.session)
            assertEquals(latest, session.experimentId)
            assertTrue(controller.sessionMatches(session))
            assertTrue(buttons(root).any { it.text == "timer" })
            assertTrue(controller.repo.lines.value.any { it.startsWith("$previous / session / stale_show_rejected:") })
            assertTrue(controller.repo.lines.value.any { it.startsWith("$latest / session / shown:") })
            assertTrue(PendingSessionShows.requests.isEmpty())
        } finally {
            session.onDestroy()
            service.destroy()
            PendingSessionShows.reset()
        }
    }

    @Test fun hideRemovesPendingShowsFromTheFakeQueue() {
        val session = ProbeSession(app)
        try {
            PendingSessionShows.reset()
            PendingSessionShows.requests.addLast(Bundle())
            session.hide()
            assertEquals(1, PendingSessionShows.hides)
            assertFalse(PendingSessionShows.deliverNext(session))
        } finally { session.onDestroy(); PendingSessionShows.reset() }
    }

    @Test fun rejectedReusedSessionLosesItsRegistrationActionsAndPendingFocus() {
        val session = ProbeSession(app)
        try {
            PendingSessionShows.reset()
            controller.select("T7")
            val old = controller.newSessionRequest("T7")
            val root = session.onCreateContentView()
            session.onShow(old, 0)
            testScheduler.runCurrent() // The focus job is waiting for window focus.
            val retainedFocus = buttons(root).single { it.text == "Request focus (T7)" }
            val latest = controller.newSessionRequest("T7")
            PendingSessionShows.requests.addLast(latest)

            session.onShow(old, 0)
            assertNull(controller.session)
            assertNull(session.experimentId)
            assertNull(session.requestGeneration)
            assertEquals(listOf("Stop"), buttons(root).map { it.text.toString() })
            retainedFocus.performClick()
            testScheduler.advanceTimeBy(2_100)
            testScheduler.runCurrent()
            assertFalse(controller.repo.lines.value.any { " / focus / " in it })
            assertEquals(0, PendingSessionShows.hides)
            assertTrue(PendingSessionShows.deliverNext(session))
            assertTrue(controller.sessionMatches(session))
        } finally { session.onDestroy(); PendingSessionShows.reset() }
    }

    @Test fun missingRequestCannotHideTheNewerQueuedShow() {
        val session = ProbeSession(app)
        try {
            PendingSessionShows.reset()
            controller.select("T10")
            PendingSessionShows.requests.addLast(controller.newSessionRequest("T10"))
            session.onShow(null, 0)
            assertNull(controller.session)
            assertEquals(0, PendingSessionShows.hides)
            assertTrue(PendingSessionShows.deliverNext(session))
            assertTrue(controller.sessionMatches(session))
        } finally { session.onDestroy(); PendingSessionShows.reset() }
    }

    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
}

private object PendingSessionShows {
    val requests = ArrayDeque<Bundle>()
    var hides = 0
    fun reset() { requests.clear(); hides = 0 }
    fun deliverNext(session: ProbeSession): Boolean {
        val args = requests.removeFirstOrNull() ?: return false
        session.onPrepareShow(args, 0)
        session.onShow(args, 0)
        return true
    }
}

@Implements(VoiceInteractionService::class)
class QueuedSessionRequestShadow : org.robolectric.shadows.ShadowService() {
    @Implementation fun setDisabledShowContext(flags: Int) = Unit
    @Implementation fun showSession(args: Bundle, flags: Int) { PendingSessionShows.requests.addLast(Bundle(args)) }
}

@Implements(VoiceInteractionSession::class)
class QueuedSessionShowShadow {
    @Implementation fun hide() {
        PendingSessionShows.hides++
        PendingSessionShows.requests.clear()
    }
}
