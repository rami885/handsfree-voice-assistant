package com.handsfree.probes

import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.ceil

/** Counts only; PCM is never retained. Monotonic time includes device sleep. */
internal class PcmMeasurement(val windowMs: Long, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val start = clock()
    private data class Counts(var samples: Long = 0, var nonZeroSamples: Long = 0, var frames: Long = 0, var nonZeroFrames: Long = 0)
    private val buckets = mutableMapOf<Pair<Long, Boolean>, Counts>()
    private var screenOffMs: Long? = null
    private var reason: String? = null
    private var stopRequest: String? = null
    private var finished = false
    @Synchronized fun isStopped(): Boolean = reason != null
    @Synchronized fun elapsed(): Long = (clock() - start).coerceAtLeast(0)
    @Synchronized fun screenOff() { if (screenOffMs == null) screenOffMs = elapsed() }
    @Synchronized fun stop(request: String) {
        if (reason != null) return
        stopRequest = request
        reason = when (request) {
            "screen_off" -> "app_stop_screen_off"
            "consent_revoked" -> "consent_revoked"
            "error" -> "error"
            "window_complete" -> "window_complete"
            else -> "service_killed"
        }
    }
    @Synchronized fun add(buffer: ShortArray, count: Int) {
        if (count <= 0) return
        // Attribute a completed read at an exact boundary to the interval that just ended.
        val bucket = buckets.getOrPut((elapsed() - 1).coerceAtLeast(0) / BUCKET_MS to (screenOffMs != null)) { Counts() }
        bucket.samples += count
        val nonZero = (0 until count).count { buffer[it].toInt() != 0 }
        bucket.nonZeroSamples += nonZero
        bucket.frames++
        if (nonZero > 0) bucket.nonZeroFrames++
    }
    @Synchronized fun finish(emit: (String, JSONObject) -> Unit) {
        if (finished) return
        finished = true
        snapshot().forEach { (event, fields) -> emit(event, fields) }
    }
    @Synchronized fun snapshot(): List<Pair<String, JSONObject>> = buildList {
        fun emit(event: String, fields: JSONObject) { add(event to fields) }
        val duration = elapsed()
        // Include empty buckets: silence or suspended reads must remain visible.
        for (index in 0..((duration - 1).coerceAtLeast(0) / BUCKET_MS)) {
            val from = index * BUCKET_MS
            val to = minOf(from + BUCKET_MS, duration)
            listOf(false, true).forEach { after ->
                val off = screenOffMs
                if ((!after && (off == null || from <= off)) || (after && off != null && to >= off)) {
                    val counts = buckets[index to after] ?: Counts()
                    emit("bucket", JSONObject().put("bucketStartMs", from).put("bucketEndMs", to)
                        .put("phase", if (after) "after_screen_off" else "before_screen_off")
                        .put("samples", counts.samples).put("nonZeroSamples", counts.nonZeroSamples)
                        .put("frames", counts.frames).put("nonZeroFrames", counts.nonZeroFrames))
                }
            }
        }
        emit("capture_end", JSONObject().put("endReason", reason ?: "service_killed").put("stopRequest", stopRequest ?: "service_destroyed")
            .put("durationMs", duration).put("durationExact", true).put("windowMs", windowMs).put("bucketMs", BUCKET_MS)
            .put("screenOffMs", screenOffMs ?: JSONObject.NULL))
    }
    companion object {
        const val BUCKET_MS = 1_000L
        const val NORMAL_WINDOW_MS = 3_000L
        const val SCREEN_OFF_WINDOW_MS = 15_000L
    }
}

internal enum class RouteOpenOutcome { SUCCESS, TIMEOUT, FAILURE }
internal data class ScoAttempt(val outcome: RouteOpenOutcome, val latencyMs: Long? = null)
internal object ScoMeasurement {
    const val ATTEMPTS = 20
    const val MIN_SUCCESSES = 19
    const val MIN_SUCCESS_RATE = MIN_SUCCESSES * 1.0 / ATTEMPTS
    const val MEDIAN_LIMIT_MS = 2_000L
    const val P90_LIMIT_MS = 2_000L
    fun summary(attempts: List<ScoAttempt>): Pair<JSONObject, Boolean> {
        val times = attempts.filter { it.outcome == RouteOpenOutcome.SUCCESS }.map { requireNotNull(it.latencyMs) }.sorted()
        val median = if (times.isEmpty()) null else if (times.size % 2 == 0) (times[times.size / 2 - 1] + times[times.size / 2]) / 2.0 else times[times.size / 2].toDouble()
        val p90 = if (times.isEmpty()) null else times[ceil(times.size * 0.9).toInt() - 1]
        val rate = if (attempts.isEmpty()) 0.0 else times.size.toDouble() / attempts.size
        val pass = attempts.size == ATTEMPTS && times.size >= MIN_SUCCESSES && median != null && median < MEDIAN_LIMIT_MS && p90 != null && p90 < P90_LIMIT_MS
        return JSONObject().put("attempts", attempts.size).put("successes", times.size)
            .put("timeouts", attempts.count { it.outcome == RouteOpenOutcome.TIMEOUT }).put("failures", attempts.count { it.outcome == RouteOpenOutcome.FAILURE })
            .put("successRate", rate).put("medianMs", median ?: JSONObject.NULL).put("p90Ms", p90 ?: JSONObject.NULL)
            .put("requiredAttempts", ATTEMPTS).put("minSuccesses", MIN_SUCCESSES).put("minSuccessRate", MIN_SUCCESS_RATE).put("medianLimitMs", MEDIAN_LIMIT_MS).put("p90LimitMs", P90_LIMIT_MS) to pass
    }
}

/** Recover a measurement even when the OS kills the process without onDestroy. */
internal class PcmCheckpoint(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("pcm_measurement", android.content.Context.MODE_PRIVATE)
    private var checkpointId = java.util.UUID.randomUUID().toString()
    companion object { private val lock = Any() }
    fun save(id: String, capture: PcmMeasurement) {
        val events = org.json.JSONArray()
        capture.snapshot().forEach { (event, fields) -> events.put(JSONObject().put("event", event).put("fields", fields)) }
        synchronized(lock) {
            check(prefs.edit().putString("pending", JSONObject().put("checkpointId", checkpointId)
                .put("experiment", id).put("events", events).toString()).commit())
        }
    }
    fun clear() = synchronized(lock) {
        val pending = prefs.getString("pending", null)
        if (pending != null && JSONObject(pending).getString("checkpointId") == checkpointId)
            check(prefs.edit().remove("pending").commit())
    }
    fun recover(repo: ProbeRepository): Boolean {
        val pending = prefs.getString("pending", null) ?: return false
        val root = JSONObject(pending)
        checkpointId = root.getString("checkpointId")
        val events = root.getJSONArray("events")
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            val fields = event.getJSONObject("fields")
            if (event.getString("event") == "capture_end") {
                fields.put("endReason", "service_killed").put("stopRequest", "process_lost")
                    .put("durationExact", false).put("durationLowerBoundMs", fields.getLong("durationMs"))
                    .put("durationMs", JSONObject.NULL)
            }
            fields.put("recovered", true)
            repo.emit(root.getString("experiment"), "pcm", event.getString("event"), fields.toString())
        }
        return true
    }
}
