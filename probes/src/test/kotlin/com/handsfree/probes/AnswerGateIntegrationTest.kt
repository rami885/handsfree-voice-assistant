package com.handsfree.probes

import android.Manifest.permission.*
import android.app.role.RoleManager
import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.*
import android.telephony.TelephonyManager.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.shadows.ShadowTelecomManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [AnswerTelecomShadow::class])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AnswerGateIntegrationTest : ProbeTest() {
    private lateinit var phone: TelephonyManager
    @Before fun ringingPhone() {
        AnswerTelecomShadow.videoStates.clear()
        listOf(RECORD_AUDIO, READ_PHONE_STATE, ANSWER_PHONE_CALLS).forEach {
            shadowOf(app).grantPermissions(it)
            controller.setConsent(it, true)
        }
        shadowOf(app.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_ASSISTANT)
        phone = app.getSystemService(TelephonyManager::class.java)
        shadowOf(phone).setTelephonyManagerForSubscriptionId(4, phone)
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(
            SubscriptionInfoBuilder.newBuilder().setId(4).buildSubscriptionInfo())
        controller.start("T1b", ProbeConfig())
        testScheduler.runCurrent()
        state(CALL_STATE_RINGING)
    }
    private fun state(state: Int) {
        (shadowOf(phone).lastTelephonyCallback as TelephonyCallback.CallStateListener).onCallStateChanged(state)
    }
    private fun results() = controller.repo.lines.value.filter { it.startsWith("T1b / answer / result:") }

    @Test fun actualPhoneCallbackRecordsSuccessBeforeInterruptClosesListening() {
        controller.answerRingingCall()
        testScheduler.runCurrent()
        assertEquals(listOf(VideoProfile.STATE_AUDIO_ONLY), AnswerTelecomShadow.videoStates)
        assertTrue(results().isEmpty())
        SystemClock.sleep(300)
        state(CALL_STATE_OFFHOOK)
        testScheduler.runCurrent()
        val result = results().single()
        assertTrue(result, result.contains("\"result\":\"answered\""))
        assertTrue(result.endsWith("[PASS]"))
        val fields = JSONObject(result.substringAfter(": ").substringBeforeLast(" ["))
        assertEquals(4, fields.getInt("subscription"))
        assertEquals(300, fields.getLong("latencyMs"))
        assertTrue(controller.repo.lines.value.any { "reason=call_state" in it })
        testScheduler.advanceTimeBy(1_001); testScheduler.runCurrent()
        assertEquals(1, results().size)
    }

    @Test fun answerTimeoutSurvivesAudioModeInterruption() {
        controller.answerRingingCall()
        testScheduler.runCurrent()
        controller.interrupt("audio_mode")
        testScheduler.advanceTimeBy(1_001); testScheduler.runCurrent()
        assertTrue(results().single().contains("\"result\":\"no_offhook_within_1s\""))
        assertFalse(results().single().endsWith("[PASS]"))
    }

    @Test fun missingAnswerPermissionIsRejectedWithoutCallingTelecom() {
        shadowOf(app).denyPermissions(ANSWER_PHONE_CALLS)
        controller.answerRingingCall()
        assertTrue(AnswerTelecomShadow.videoStates.isEmpty())
        assertTrue(results().single().contains("\"result\":\"rejected_by_guard\""))
    }

    @Test fun endedRingingRecordsNotRingingWithoutCallingTelecom() {
        state(CALL_STATE_IDLE)
        controller.answerRingingCall()
        assertTrue(AnswerTelecomShadow.videoStates.isEmpty())
        assertTrue(results().single().contains("\"result\":\"not_ringing\""))
    }

    private fun focusedSession(focusResult: Int, block: () -> Unit) {
        val manager = app.getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(focusResult)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val session = ProbeSession(app)
        try {
            val content = session.onCreateContentView()
            activity.get().setContentView(content)
            activity.windowFocusChanged(true)
            assertTrue(content.hasWindowFocus())
            // Attaching the activity drains the initial subscription callback (IDLE).
            state(CALL_STATE_RINGING)
            session.onShow(controller.newSessionRequest("T1b"), 0)
            testScheduler.runCurrent()
            val request = shadowOf(manager).lastAudioFocusRequest
            assertNotNull("T1b must request audio focus automatically", request)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.durationHint)
            assertFalse(controller.repo.lines.value.any { it.startsWith("T7 / focus /") })
            assertTrue("Focus must not complete the answer gate", results().isEmpty())
            block()
        } finally { session.onDestroy(); activity.pause().stop().destroy() }
    }

    @Test @Config(shadows = [AnswerTelecomShadow::class, SessionLaunchShadow::class])
    fun t1bSessionRequestsTransientFocusAndDeniedFocusDoesNotBlockAnswer() =
        focusedSession(AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            assertTrue(controller.repo.lines.value.any { it.startsWith("T1b / focus / request_result: FAILED") })
            controller.answerRingingCall()
            val hides = SessionLaunchShadow.hides
            state(CALL_STATE_OFFHOOK)
            assertTrue(results().single(), results().single().endsWith("[PASS]"))
            assertEquals(hides + 1, SessionLaunchShadow.hides)
            assertNull(controller.session)
        }

    @Test fun grantedFocusCannotPassWithoutOffhook() = focusedSession(AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        assertTrue(controller.repo.lines.value.any { it.startsWith("T1b / focus / request_result: GRANTED") })
        controller.answerRingingCall()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1_001); testScheduler.runCurrent()
        assertTrue(results().single().contains("\"result\":\"no_offhook_within_1s\""))
        assertFalse(results().single().endsWith("[PASS]"))
    }

    @Test fun screenStateIsRecordedBeforeShowingIncomingSessionAndInAnswerResult() {
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        controller.screened(null)
        val screen = controller.repo.lines.value.single { it.startsWith("T1b / screening / screen_state:") }
        assertTrue(screen.contains("\"interactive\":false"))
        controller.answerRingingCall()
        power.setIsInteractive(true)
        state(CALL_STATE_OFFHOOK)
        assertTrue(results().single().contains("\"interactive\":false"))
        assertTrue(results().single().endsWith("[PASS]"))
    }
}

@Implements(TelecomManager::class)
class AnswerTelecomShadow : ShadowTelecomManager() {
    @Implementation fun acceptRingingCall(videoState: Int) { videoStates += videoState }
    companion object { val videoStates = mutableListOf<Int>() }
}
