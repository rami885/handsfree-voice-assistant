package com.handsfree.probes

import android.Manifest.permission.*
import android.app.role.RoleManager
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
}

@Implements(TelecomManager::class)
class AnswerTelecomShadow : ShadowTelecomManager() {
    @Implementation fun acceptRingingCall(videoState: Int) { videoStates += videoState }
    companion object { val videoStates = mutableListOf<Int>() }
}
