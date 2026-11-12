package com.handsfree.probes

import android.os.SystemClock
import android.telephony.TelephonyManager.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Main-thread gate. Its timeout belongs to the controller, not the interrupted speech job. */
internal class AnswerMeasurement(
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val interactive: () -> Boolean,
    private val record: (JSONObject, Boolean) -> Unit,
) {
    companion object { const val WINDOW_MS = 1_000L }
    private class Attempt(val subscription: Int, val start: Long, val interactive: Boolean) {
        var continuousRinging = true
        var timeout: Job? = null
        var actionReturned = false
        var offhookLatency: Long? = null
    }
    private var pending: Attempt? = null

    fun answer(states: Map<Int, Int?>, guard: () -> Boolean, action: () -> Unit) {
        val screenInteractive = interactive()
        val ringing = states.filterValues { it == CALL_STATE_RINGING }.keys
        if (ringing.isEmpty()) {
            emit("not_ringing", null, null, screenInteractive)
            return
        }
        val subscription = ringing.singleOrNull()
        if (pending != null || subscription == null || states.values.any { it != CALL_STATE_IDLE && it != CALL_STATE_RINGING } || !guard()) {
            emit("rejected_by_guard", subscription, null, screenInteractive)
            return
        }
        val attempt = Attempt(subscription, clock(), screenInteractive)
        pending = attempt
        attempt.timeout = scope.launch {
            // Include an OFFHOOK callback at exactly 1000 ms; later callbacks cannot pass.
            delay(WINDOW_MS + 1)
            if (pending === attempt) finish(attempt, "no_offhook_within_1s")
        }
        try {
            action()
        } catch (error: Throwable) {
            finish(attempt, "platform_failure")
            throw error
        }
        attempt.actionReturned = true
        // A synchronous callback is provisional until the action returns without throwing.
        attempt.offhookLatency?.let { finish(attempt, "answered", it) }
    }

    fun stateChanged(subscription: Int, previous: Int?, state: Int) {
        val attempt = pending ?: return
        if (subscription != attempt.subscription) {
            if (state == CALL_STATE_OFFHOOK) attempt.continuousRinging = false
            return
        }
        val latency = clock() - attempt.start
        if (attempt.continuousRinging && previous == CALL_STATE_RINGING && state == CALL_STATE_OFFHOOK && latency <= WINDOW_MS) {
            if (attempt.actionReturned) finish(attempt, "answered", latency)
            else attempt.offhookLatency = latency
        } else if (previous != CALL_STATE_RINGING || state != CALL_STATE_RINGING) {
            // IDLE, unknown state or a new ringing cycle must not validate the old answer.
            attempt.continuousRinging = false
        }
    }

    private fun finish(attempt: Attempt, result: String, latency: Long = clock() - attempt.start) {
        if (pending !== attempt) return
        pending = null
        attempt.timeout?.cancel()
        emit(result, attempt.subscription, latency, attempt.interactive)
    }

    private fun emit(result: String, subscription: Int?, latency: Long?, interactive: Boolean) {
        record(JSONObject().put("result", result).put("subscription", subscription ?: JSONObject.NULL)
            .put("latencyMs", latency ?: JSONObject.NULL).put("windowMs", WINDOW_MS)
            .put("interactive", interactive), result == "answered")
    }
}
