package com.handsfree.probes

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [SessionLaunchShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLaunchTest : ProbeTest() {
    @Test fun sessionLaunchUsesAssistantActivity() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val app = RuntimeEnvironment.getApplication() as ProbesApp
            val session = ProbeSession(app)
            app.controller.select("T2")
            session.onShow(controller.newSessionRequest("T2"), 0)
            SessionLaunchShadow.assistant = null
            SessionLaunchShadow.voice = null
            app.controller.launchFromSession("timer", session)
            runCurrent()
            assertNotNull(SessionLaunchShadow.assistant)
            assertEquals("android.intent.action.SET_TIMER", SessionLaunchShadow.assistant?.action)
            assertNull(SessionLaunchShadow.voice)
        } finally { Dispatchers.resetMain() }
    }
}
@Implements(VoiceInteractionSession::class)
class SessionLaunchShadow {
    @Implementation fun startAssistantActivity(intent: Intent) { assistant = intent }
    @Implementation fun startVoiceActivity(intent: Intent) { voice = intent }
    @Implementation fun hide() { hides++ }
    companion object { var assistant: Intent? = null; var voice: Intent? = null; var hides = 0 }
}
