package com.handsfree.guard

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// This schema deliberately cannot contain an action snapshot or user content.
data class AuditEvent(val tool: String, val decision: AuditDecision, val reason: AuditReason, val durationMs: Long, val estimatedCostMicros: Long) {
    init {
        require(tool.matches(Regex("[a-z][a-z_]{0,63}")))
        require(durationMs >= 0 && estimatedCostMicros >= 0)
    }
}
enum class AuditDecision { ALLOW, NEED_CONFIRM, DENY, CANCELLED, IGNORED, DONE, FAILED }
enum class AuditReason {
    NONE, USER_CANCELLED, SCREEN_LOCKED, LATE_PLANNER_RESULT,
    FORBIDDEN, INVALID_TOOL, UNTRUSTED_ORIGIN, SYSTEM_PERMISSION, INTERNAL_CONSENT, ROLE_LOST, LOCKED, ACTIVE_CALL,
}
fun interface AuditSink { fun record(event: AuditEvent) }

class LocalAudit(private val sink: AuditSink) {
    fun record(spec: ToolSpec, decision: Decision, durationMs: Long, estimatedCostMicros: Long) {
        val outcome = when (decision) {
            is Decision.Allow -> AuditDecision.ALLOW
            is Decision.NeedConfirm -> AuditDecision.NEED_CONFIRM
            is Decision.Deny -> AuditDecision.DENY
        }
        sink.record(AuditEvent(spec.name, outcome, if (decision is Decision.Deny) AuditReason.valueOf(decision.reason.name) else AuditReason.NONE, durationMs, estimatedCostMicros))
    }
}

class FileAuditSink(private val path: Path) : AuditSink {
    @Synchronized
    override fun record(event: AuditEvent) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, "${event.tool},${event.decision},${event.reason},${event.durationMs},${event.estimatedCostMicros}\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
