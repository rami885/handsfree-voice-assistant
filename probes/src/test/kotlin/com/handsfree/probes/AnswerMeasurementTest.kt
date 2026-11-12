package com.handsfree.probes

import android.telephony.TelephonyManager.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class AnswerMeasurementTest {
    private val results = mutableListOf<Pair<JSONObject, Boolean>>()
    private var interactive = true
    private fun TestScope.gate() = AnswerMeasurement(this, { testScheduler.currentTime }, { interactive }) { fields, success -> results += fields to success }
    private fun result(expected: String, latency: Long?, success: Boolean = false) {
        val (fields, passed) = results.single()
        assertEquals(expected, fields.getString("result"))
        if (latency == null) assertTrue(fields.isNull("latencyMs")) else assertEquals(latency, fields.getLong("latencyMs"))
        assertEquals(1_000, fields.getLong("windowMs"))
        assertEquals(success, passed)
    }

    @Test fun answeredRequiresSameSubscriptionOffhookAfterActualActionAndRecordsLatency() = runTest {
        val gate = gate()
        var actions = 0
        gate.answer(mapOf(4 to CALL_STATE_RINGING, 8 to CALL_STATE_IDLE), { true }) { actions++ }
        runCurrent()
        assertEquals(1, actions)
        assertTrue("Issuing the action is not success", results.isEmpty())
        advanceTimeBy(350)
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        result("answered", 350, true)
        assertEquals(4, results.single().first.getInt("subscription"))
        advanceUntilIdle()
        assertEquals(1, results.size)
    }

    @Test fun noOffhookWithinOneSecondFailsEvenWhenActionWasIssued() = runTest {
        val gate = gate()
        var actions = 0
        gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) { actions++ }
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, actions)
        assertTrue(results.isEmpty())
        advanceTimeBy(1); runCurrent()
        result("no_offhook_within_1s", 1_001)
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        assertEquals(1, results.size)
    }

    @Test fun rejectedByGuardDoesNotCallAnswerAction() = runTest {
        gate().answer(mapOf(4 to CALL_STATE_RINGING), { false }) { fail("Guard rejected the answer") }
        result("rejected_by_guard", null)
        assertEquals(4, results.single().first.getInt("subscription"))
    }

    @Test fun notRingingDoesNotCallAnswerAction() = runTest {
        gate().answer(mapOf(4 to CALL_STATE_IDLE), { true }) { fail("No ringing call") }
        result("not_ringing", null)
        assertTrue(results.single().first.isNull("subscription"))
    }

    @Test fun ambiguousOrUnknownOrActiveSubscriptionsRejectAction() = runTest {
        listOf(
            mapOf(4 to CALL_STATE_RINGING, 8 to CALL_STATE_RINGING),
            mapOf(4 to CALL_STATE_RINGING, 8 to null),
            mapOf(4 to CALL_STATE_RINGING, 8 to CALL_STATE_OFFHOOK),
        ).forEach { states ->
            results.clear()
            gate().answer(states, { true }) { fail("Unsafe subscription snapshot") }
            result("rejected_by_guard", null)
        }
    }

    @Test fun offhookOnAnotherSubscriptionCannotPass() = runTest {
        val gate = gate()
        gate.answer(mapOf(4 to CALL_STATE_RINGING, 8 to CALL_STATE_IDLE), { true }) { }
        runCurrent(); advanceTimeBy(200)
        gate.stateChanged(8, CALL_STATE_IDLE, CALL_STATE_OFFHOOK)
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        assertTrue(results.isEmpty())
        advanceUntilIdle()
        result("no_offhook_within_1s", 1_001)
    }

    @Test fun endedCallOrUnknownStateCannotBeReplacedByAnotherCall() = runTest {
        listOf<Int?>(CALL_STATE_IDLE, null).forEach { intermediate ->
            results.clear()
            val gate = gate()
            gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) { }
            runCurrent()
            gate.stateChanged(4, intermediate, CALL_STATE_RINGING)
            gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
            assertTrue(results.isEmpty())
            advanceUntilIdle()
            result("no_offhook_within_1s", 1_001)
        }
    }

    @Test fun exactOneSecondTransitionPassesButLaterTransitionDoesNot() = runTest {
        val gate = gate()
        gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) { }
        runCurrent(); advanceTimeBy(1_000)
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        result("answered", 1_000, true)
        results.clear()
        gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) { }
        runCurrent(); advanceTimeBy(1_001)
        // Even before the scheduled timeout runs, a late transition cannot pass.
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        assertTrue(results.isEmpty())
        runCurrent()
        result("no_offhook_within_1s", 1_001)
    }

    @Test fun throwingActionTerminatesAttemptAndIgnoresLaterOffhook() = runTest {
        val gate = gate()
        val error = SecurityException("Answer denied")
        assertSame(error, assertThrows(SecurityException::class.java) {
            gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) { throw error }
        })
        result("platform_failure", 0)
        runCurrent()
        advanceTimeBy(350)
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        advanceUntilIdle()
        assertEquals("The cancelled timeout must not advance time to 1001 ms", 350, testScheduler.currentTime)
        result("platform_failure", 0)

        // Failure also releases the gate for a later, independent attempt.
        results.clear()
        gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) { }
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        result("answered", 0, true)
    }

    @Test fun synchronousCallbackPassesOnlyAfterActionReturnsSuccessfully() = runTest {
        val gate = gate()
        gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) {
            gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
            assertTrue(results.isEmpty())
        }
        result("answered", 0, true)
        results.clear()
        assertThrows(SecurityException::class.java) {
            gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) {
                gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
                throw SecurityException("Answer denied after callback")
            }
        }
        advanceUntilIdle()
        result("platform_failure", 0)
    }

    @Test fun everyResultRecordsScreenStateAtAttemptStart() = runTest {
        val gate = gate()
        interactive = false
        gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) { }
        interactive = true
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        assertFalse(results.single().first.getBoolean("interactive"))
        results.clear()
        gate.answer(mapOf(4 to CALL_STATE_IDLE), { true }) { fail("No ringing call") }
        assertTrue(results.single().first.getBoolean("interactive"))
        results.clear()
        interactive = false
        gate.answer(mapOf(4 to CALL_STATE_RINGING), { false }) { fail("Guard rejected") }
        assertFalse(results.single().first.getBoolean("interactive"))
    }

    @Test fun boundaryDecisionAndRecordedLatencyUseTheSameClockSample() = runTest {
        var now = 0L
        val gate = AnswerMeasurement(this, { now++ }, { true }) { fields, success -> results += fields to success }
        gate.answer(mapOf(4 to CALL_STATE_RINGING), { true }) { }
        now = 1_000
        gate.stateChanged(4, CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
        result("answered", 1_000, true)
    }
}
