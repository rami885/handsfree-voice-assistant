package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import com.handsfree.guard.Decision
import com.handsfree.guard.DenyReason
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MeasurementRegressionTest : ProbeTest() {
    private fun t3(measure: Boolean) {
        shadowOf(app).grantPermissions(RECORD_AUDIO)
        controller.setConsent(RECORD_AUDIO, true)
        controller.start("T3", ProbeConfig(screenOffMeasurement = measure))
        while (shadowOf(app).nextStoppedService != null) { }
    }
    private fun screenOff() {
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun micStopped() = generateSequence { shadowOf(app).nextStoppedService }
        .any { it.component?.className == MicrophoneService::class.java.name }

    @Test fun selectedScreenOffModeKeepsTheRunAndMicrophoneAlive() {
        t3(true)
        screenOff()
        assertFalse(micStopped())
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        val started = generateSequence { shadowOf(app).nextStartedService }.toList()
        assertTrue("The delayed T3 job must survive screen off", started.any {
            it.component?.className == MicrophoneService::class.java.name && it.getBooleanExtra("screenOffMeasurement", false)
        })
        assertFalse(controller.repo.lines.value.any { "reason=screen_off" in it })
    }

    @Test fun normalModeStillStopsAndRecordsScreenOffReason() {
        t3(false)
        var now = 0L
        val capture = PcmMeasurement(3_000) { now }
        controller.microphoneMeasurement = capture
        now = 750
        screenOff()
        assertTrue(micStopped())
        var summary: JSONObject? = null
        capture.finish { event, fields -> if (event == "capture_end") summary = fields }
        assertEquals("app_stop_screen_off", summary!!.getString("endReason"))
        assertEquals(750, summary!!.getLong("durationMs"))
    }

    @Test fun consentRevocationAndActiveCallStopEvenInMeasurementMode() {
        listOf("consent_revoked", "call_state", "audio_mode").forEach { reason ->
            t3(true)
            val capture = PcmMeasurement(15_000) { 0L }
            controller.microphoneMeasurement = capture
            if (reason == "consent_revoked") controller.setConsent(RECORD_AUDIO, false) else controller.interrupt(reason)
            assertTrue("Immediate stop required for $reason", micStopped())
            var end: JSONObject? = null
            capture.finish { event, fields -> if (event == "capture_end") end = fields }
            assertEquals(if (reason == "consent_revoked") reason else "service_killed", end!!.getString("endReason"))
            assertEquals(reason, end!!.getString("stopRequest"))
        }
    }

    @Test fun bucketsSeparateBeforeAndAfterScreenOffIncludingEmptySeconds() {
        var now = 0L
        val capture = PcmMeasurement(4_000) { now }
        capture.add(shortArrayOf(0, 7), 2)
        now = 500; capture.screenOff()
        capture.add(shortArrayOf(0, 0), 2)
        now = 2_100; capture.add(shortArrayOf(5), 1)
        now = 4_000; capture.stop("window_complete")
        val buckets = mutableListOf<JSONObject>()
        var summary: JSONObject? = null
        capture.finish { event, fields -> if (event == "bucket") buckets += fields else summary = fields }
        val before = buckets.single { it.getString("phase") == "before_screen_off" }
        assertEquals(2, before.getLong("samples")); assertEquals(1, before.getLong("nonZeroFrames"))
        val after = buckets.filter { it.getString("phase") == "after_screen_off" }
        assertEquals(4, after.size)
        assertEquals(0, after.single { it.getLong("bucketStartMs") == 1_000L }.getLong("samples"))
        assertEquals(3, after.sumOf { it.getLong("samples") })
        assertEquals(1, after.sumOf { it.getLong("nonZeroFrames") })
        assertEquals("window_complete", summary!!.getString("endReason"))
        assertEquals(4_000, summary!!.getLong("durationMs"))
        assertEquals(500, summary!!.getLong("screenOffMs"))
    }

    @Test fun allCaptureEndReasonsAreDistinctAndFirstReasonWins() {
        mapOf("window_complete" to "window_complete", "screen_off" to "app_stop_screen_off",
            "consent_revoked" to "consent_revoked", "service_destroyed" to "service_killed", "error" to "error").forEach { (stop, expected) ->
            val capture = PcmMeasurement(3_000) { 0L }
            capture.stop(stop); capture.stop("service_destroyed")
            capture.finish { event, fields -> if (event == "capture_end") assertEquals(expected, fields.getString("endReason")) }
        }
    }

    @Test fun oneFastSuccessAndNineteenTimeoutsCannotPassSco() {
        val attempts = listOf(ScoAttempt(RouteOpenOutcome.SUCCESS, 50)) + List(19) { ScoAttempt(RouteOpenOutcome.TIMEOUT) }
        val (fields, pass) = ScoMeasurement.summary(attempts)
        assertFalse(pass)
        assertEquals(20, fields.getInt("attempts")); assertEquals(1, fields.getInt("successes"))
        assertEquals(19, fields.getInt("timeouts")); assertEquals(0, fields.getInt("failures"))
        assertEquals(0.05, fields.getDouble("successRate"), 0.0)
        assertEquals(50, fields.getInt("p90Ms"))
    }

    @Test fun scoVerdictRequiresNineteenSuccessesAndLatencyThresholdsAndFullTrial() {
        fun successes(n: Int, latency: Long) = List(n) { ScoAttempt(RouteOpenOutcome.SUCCESS, latency) }
        assertTrue(ScoMeasurement.summary(successes(19, 100) + List(1) { ScoAttempt(RouteOpenOutcome.FAILURE) }).second)
        assertFalse(ScoMeasurement.summary(successes(18, 100) + List(2) { ScoAttempt(RouteOpenOutcome.FAILURE) }).second)
        assertFalse(ScoMeasurement.summary(successes(20, 2_000)).second)
        assertFalse(ScoMeasurement.summary(successes(17, 100) + successes(3, 2_000)).second)
        assertFalse(ScoMeasurement.summary(successes(1, 100)).second)
        val (empty, pass) = ScoMeasurement.summary(List(20) { ScoAttempt(RouteOpenOutcome.FAILURE) })
        assertFalse(pass); assertTrue(empty.isNull("medianMs")); assertTrue(empty.isNull("p90Ms"))
    }

    @Test fun readAndSendEvaluateLockGuardWithoutLaunchingAnythingOrNetworkPermission() {
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceSecure(true)
        listOf(false, true).forEach { locked ->
            keyguard.setKeyguardLocked(locked)
            listOf(false, true).forEach { personal ->
                controller.start("T10", ProbeConfig(personalResultsOnLock = personal))
                listOf("read", "send").forEach { kind ->
                    val decision = controller.evaluateLockAction(kind)
                    if (locked && !personal) assertEquals(DenyReason.LOCKED, (decision as Decision.Deny).reason)
                    else assertTrue(decision is Decision.Allow)
                }
            }
        }
        assertNull(shadowOf(app).nextStartedActivity)
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("android.permission.INTERNET" in permissions)
        assertEquals(8, controller.repo.lines.value.count { "action_decision" in it })
    }
    @Test fun revokingPersonalResultsImmediatelyChangesTheLockDecision() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        controller.setConsent("personal_results_on_lock", true)
        controller.start("T10", ProbeConfig(personalResultsOnLock = true))
        assertTrue(controller.evaluateLockAction("send") is Decision.Allow)
        controller.setConsent("personal_results_on_lock", false)
        assertEquals(DenyReason.LOCKED, (controller.evaluateLockAction("send") as Decision.Deny).reason)
        assertEquals(DenyReason.LOCKED, (controller.evaluateLockAction("read") as Decision.Deny).reason)
    }

    @Test fun readAtTheWindowBoundaryIsNotLostFromBuckets() {
        var now = 0L
        val capture = PcmMeasurement(1_000) { now }
        now = 1_000; capture.add(shortArrayOf(9), 1); capture.stop("window_complete")
        var samples = 0L
        capture.finish { event, fields -> if (event == "bucket") samples += fields.getLong("samples") }
        assertEquals(1, samples)
    }

}
