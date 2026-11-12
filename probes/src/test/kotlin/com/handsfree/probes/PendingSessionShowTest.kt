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
            buttons(root).single { it.text == "Stop" }.performClick()
            assertFalse(PendingSessionShows.visible)
            assertNull(controller.session)
            assertFalse(controller.hasPendingSessionRequest)
            assertTrue(controller.repo.lines.value.any { "reason=session_stop;" in it })
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

    @Test fun stopOnAnInertSessionHidesItWithoutStoppingTheNewerRequest() {
        val session = ProbeSession(app)
        try {
            PendingSessionShows.reset()
            controller.select("T2")
            PendingSessionShows.requests.addLast(controller.newSessionRequest("T2"))
            controller.select("T10")
            PendingSessionShows.requests.addLast(controller.newSessionRequest("T10"))
            val root = session.onCreateContentView()
            assertTrue(PendingSessionShows.deliverNext(session))
            assertTrue(PendingSessionShows.visible)
            assertFalse(controller.sessionMatches(session))

            buttons(root).single { it.text == "Stop" }.performClick()
            testScheduler.runCurrent()

            assertFalse(PendingSessionShows.visible)
            assertEquals(1, PendingSessionShows.hides)
            assertTrue(controller.hasPendingSessionRequest)
            assertFalse(controller.repo.lines.value.any { " / interruption / " in it })
        } finally { session.onDestroy(); PendingSessionShows.reset() }
    }

    @Test fun failedNewerShowHidesALateStaleSession() = failedNewerRequest("show_failed")

    @Test fun assistantNotReadyHidesALateStaleSession() = failedNewerRequest("assistant_not_ready")

    @Test fun missingAssistantHidesALateStaleSession() = failedNewerRequest("missing_service")

    @Test fun staleRequestEarlyReturnHidesALateStaleSession() = failedNewerRequest("stale_request_rejected")

    private fun failedNewerRequest(failure: String) {
        val roles = shadowOf(app.getSystemService(RoleManager::class.java))
        roles.addHeldRole(RoleManager.ROLE_ASSISTANT)
        val service = Robolectric.buildService(ProbeVoiceService::class.java).create()
        val session = ProbeSession(app)
        try {
            PendingSessionShows.reset()
            service.get().onReady()
            controller.select("T2")
            controller.showSession("T2")
            controller.select("T10")
            when (failure) {
                "show_failed" -> PendingSessionShows.failShow = true
                "assistant_not_ready" -> roles.removeHeldRole(RoleManager.ROLE_ASSISTANT)
                "missing_service" -> service.get().onShutdown()
                "stale_request_rejected" -> controller.newSessionRequest("T10")
            }
            controller.showSession(if (failure == "stale_request_rejected") "T2" else "T10")
            assertEquals(1, PendingSessionShows.requests.size)
            val event = if (failure == "missing_service") "assistant_not_ready" else failure
            assertTrue(controller.repo.lines.value.any { " / session / $event:" in it })
            session.onCreateContentView()

            assertTrue(PendingSessionShows.deliverNext(session))

            assertFalse(PendingSessionShows.visible)
            assertFalse(controller.hasPendingSessionRequest)
            assertNull(controller.session)
            assertNull(session.experimentId)
            assertEquals(1, PendingSessionShows.hides)
        } finally { session.onDestroy(); service.destroy(); PendingSessionShows.reset() }
    }

    @Test fun interruptionHidesAVisibleAcceptedSession() {
        val session = ProbeSession(app)
        try {
            PendingSessionShows.reset()
            controller.select("T2")
            PendingSessionShows.requests.addLast(controller.newSessionRequest("T2"))
            session.onCreateContentView()
            assertTrue(PendingSessionShows.deliverNext(session))
            assertTrue(PendingSessionShows.visible)
            assertTrue(controller.sessionMatches(session))

            controller.interrupt("audio_mode")

            assertFalse(PendingSessionShows.visible)
            assertFalse(controller.hasPendingSessionRequest)
            assertNull(controller.session)
            assertEquals(1, PendingSessionShows.hides)
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
    var visible = false
    var failShow = false
    fun reset() { requests.clear(); hides = 0; visible = false; failShow = false }
    fun deliverNext(session: ProbeSession): Boolean {
        val args = requests.removeFirstOrNull() ?: return false
        session.onPrepareShow(args, 0)
        visible = true
        session.onShow(args, 0)
        return true
    }
}

@Implements(VoiceInteractionService::class)
class QueuedSessionRequestShadow : org.robolectric.shadows.ShadowService() {
    @Implementation fun setDisabledShowContext(flags: Int) = Unit
    @Implementation fun showSession(args: Bundle, flags: Int) {
        if (PendingSessionShows.failShow) throw IllegalStateException("show failed")
        PendingSessionShows.requests.addLast(Bundle(args))
    }
}

@Implements(VoiceInteractionSession::class)
class QueuedSessionShowShadow {
    @Implementation fun hide() {
        PendingSessionShows.hides++
        PendingSessionShows.visible = false
        PendingSessionShows.requests.clear()
    }
}
