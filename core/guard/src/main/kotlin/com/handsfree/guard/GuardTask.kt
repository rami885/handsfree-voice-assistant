package com.handsfree.guard

enum class TaskState { PLANNING, AWAITING_CONFIRM, EXECUTING, DONE, CANCELLED, FAILED }

class GuardTask(private val spec: ToolSpec, private val audit: AuditSink) {
    @Volatile var state: TaskState = TaskState.PLANNING
        private set
    private var decision: Decision? = null

    @Synchronized
    fun plannerResult(result: Decision): Boolean {
        if (state != TaskState.PLANNING) {
            audit.record(AuditEvent(spec.name, AuditDecision.IGNORED, AuditReason.LATE_PLANNER_RESULT, 0, 0))
            return false
        }
        decision = when (result) {
            is Decision.Allow -> result.copy(snapshot = result.snapshot.frozen())
            is Decision.NeedConfirm -> result.copy(snapshot = result.snapshot.frozen())
            is Decision.Deny -> result
        }
        state = when (result) {
            is Decision.Allow, is Decision.NeedConfirm -> TaskState.AWAITING_CONFIRM
            is Decision.Deny -> TaskState.FAILED
        }
        return true
    }

    @Synchronized
    fun execute(current: Decision): Boolean {
        if (state != TaskState.AWAITING_CONFIRM || current !is Decision.Allow) return false
        val planned = when (val ready = decision) {
            is Decision.Allow -> ready.snapshot
            is Decision.NeedConfirm -> ready.snapshot
            else -> return false
        }
        if (planned != current.snapshot) return false
        state = TaskState.EXECUTING
        return true
    }

    @Synchronized
    fun cancel(screenLocked: Boolean = false): Boolean {
        if (state != TaskState.PLANNING && state != TaskState.AWAITING_CONFIRM) return false
        state = TaskState.CANCELLED
        decision = null
        audit.record(AuditEvent(spec.name, AuditDecision.CANCELLED, if (screenLocked) AuditReason.SCREEN_LOCKED else AuditReason.USER_CANCELLED, 0, 0))
        return true
    }

    @Synchronized
    fun finish(success: Boolean): Boolean {
        if (state != TaskState.EXECUTING) return false
        state = if (success) TaskState.DONE else TaskState.FAILED
        audit.record(AuditEvent(spec.name, if (success) AuditDecision.DONE else AuditDecision.FAILED, AuditReason.NONE, 0, 0))
        return true
    }
}
