package com.handsfree.probes

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [SessionLaunchShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCancellationTest : ProbeTest() {
    @Test fun hidingSessionCancelsPendingAction() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val pending = controller.scope.launch { awaitCancellation() }
        try {
            val session = ProbeSession(app)
            controller.session = session
            // Only our own task handle is injected; no framework internals are reflected.
            ProbeController::class.java.getDeclaredField("actionJob").apply { isAccessible = true }.set(controller, pending)
            session.onHide()
            assertTrue(pending.isCancelled)
            assertNull(controller.session)
        } finally { pending.cancelAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun hiddenSessionCannotLaunchLateAction() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val session = ProbeSession(app)
            controller.session = session
            session.onHide()
            SessionLaunchShadow.assistant = null
            SessionLaunchShadow.voice = null
            controller.launchFromSession("timer", session)
            runCurrent()
            assertNull(SessionLaunchShadow.assistant)
            assertNull(SessionLaunchShadow.voice)
        } finally { Dispatchers.resetMain() }
    }
}
