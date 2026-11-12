package com.handsfree.probes

import android.app.role.RoleManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
@Config(sdk = [31], shadows = [SessionRequestShadow::class, SessionLaunchShadow::class])
class StaleSessionTest : ProbeTest() {
    @Test fun lateOnShowCannotRestoreThePreviousExperimentOrItsActions() {
        shadowOf(app.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_ASSISTANT)
        val service = Robolectric.buildService(ProbeVoiceService::class.java).create()
        val session = ProbeSession(app)
        try {
            service.get().onReady()
            controller.select("T2")
            SessionRequestShadow.requested = null
            controller.showSession("T2")
            val late = checkNotNull(SessionRequestShadow.requested)
            assertEquals("T2", late.getString("experiment"))
            controller.select("T10")
            val root = session.onCreateContentView()
            val hides = SessionLaunchShadow.hides
            session.onShow(late, 0)
            testScheduler.runCurrent()
            assertEquals("T10", controller.currentId)
            assertNull(controller.session)
            assertEquals(hides + 1, SessionLaunchShadow.hides)
            assertFalse(buttons(root).any { it.text in listOf("call", "play", "timer", "app") })
            assertTrue(controller.repo.lines.value.any { it.startsWith("T2 / session / stale_show_rejected:") })
            assertFalse(controller.repo.lines.value.any { it.startsWith("T2 / session / shown:") || it.startsWith("T2 / session_voice /") })
            assertFalse(controller.repo.lines.value.any { " / interruption / " in it || " / auto_export:" in it })
            SessionLaunchShadow.assistant = null
            controller.launchFromSession("timer", session)
            testScheduler.runCurrent()
            assertNull(SessionLaunchShadow.assistant)
        } finally { session.onDestroy(); service.destroy() }
    }

    @Test fun switchingAwayAndBackStillRejectsTheOldRequestGeneration() {
        controller.select("T2")
        val late = controller.newSessionRequest("T2")
        controller.select("T10")
        controller.select("T2")
        val latest = controller.newSessionRequest("T2")
        val session = ProbeSession(app)
        try {
            session.onShow(late, 0)
            assertNull(controller.session)
            session.onShow(latest, 0)
            assertSame(session, controller.session)
        } finally { session.onDestroy() }
    }

    @Test fun staleButtonsAndLaunchUseTheirOwnExperimentAndCannotActOnTheNewSelection() {
        controller.select("T2")
        val session = ProbeSession(app)
        try {
            val root = session.onCreateContentView()
            session.onShow(controller.newSessionRequest("T2"), 0)
            testScheduler.runCurrent()
            val timer = buttons(root).single { it.text == "timer" }
            val stop = buttons(root).single { it.text == "Stop" }
            controller.select("T10")
            controller.start("T10", ProbeConfig(destination = "Paris"))
            testScheduler.runCurrent()
            // Even a retained controller reference must not authorize a session
            // whose own experiment and request no longer match the selection.
            controller.session = session
            val before = controller.repo.lines.value.size
            SessionLaunchShadow.assistant = null
            timer.performClick()
            stop.performClick()
            controller.launchFromSession("timer", session)
            testScheduler.runCurrent()
            assertNull(SessionLaunchShadow.assistant)
            assertEquals("T10", controller.currentId)
            assertEquals("Paris", controller.config.destination)
            val added = controller.repo.lines.value.drop(before)
            assertTrue(added.any { it.startsWith("T2 / session / hidden_action_rejected:") })
            assertFalse(added.any { it.startsWith("T10 /") })
        } finally { session.onDestroy() }
    }

    @Test fun systemInvocationGetsThePersistedSelectionAndAGeneration() {
        controller.select("T7")
        app.controller = newController(newRepository())
        val session = ProbeSession(app)
        try {
            session.onPrepareShow(null, 0)
            session.onShow(null, 0)
            assertEquals("T7", session.experimentId)
            assertNotNull(session.requestGeneration)
            assertSame(session, controller.session)
        } finally { session.onDestroy() }
    }

    @Test fun systemInvocationPreparedBeforeSwitchCannotShowTheOldSelection() {
        controller.select("T2")
        val session = ProbeSession(app)
        try {
            session.onPrepareShow(null, 0)
            controller.select("T10")
            session.onShow(null, 0)
            assertNull(controller.session)
            assertTrue(controller.repo.lines.value.any { it.startsWith("T2 / session / stale_show_rejected:") })
        } finally { session.onDestroy() }
    }

    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
}

@Implements(VoiceInteractionService::class)
class SessionRequestShadow : org.robolectric.shadows.ShadowService() {
    @Implementation fun setDisabledShowContext(flags: Int) = Unit
    @Implementation fun showSession(args: Bundle, flags: Int) { requested = Bundle(args) }
    companion object { var requested: Bundle? = null }
}
