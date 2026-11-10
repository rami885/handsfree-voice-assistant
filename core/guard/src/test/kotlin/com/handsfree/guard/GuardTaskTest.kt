package com.handsfree.guard

import org.junit.Assert.*
import org.junit.Test

class GuardTaskTest {
    private val spec = ToolSpec("call", emptyMap(), Capability.NONE, Sensitivity.CONFIRM)
    private val snapshot = ActionSnapshot("call", ActionKind.CALL_OUT, origins = mapOf("tool" to Origin.USER_UTTERANCE))
    private val allow = Decision.Allow(snapshot)
    private val need = Decision.NeedConfirm("Confirm?", snapshot)
    @Test fun confirmedExecutionAndTerminalStates() {
        for (successful in listOf(true, false)) {
            val events = mutableListOf<AuditEvent>()
            val task = GuardTask(spec, events::add)
            assertEquals(TaskState.PLANNING, task.state)
            assertFalse(task.finish(true))
            assertFalse(task.execute(allow))
            assertTrue(task.plannerResult(need))
            assertEquals(TaskState.AWAITING_CONFIRM, task.state)
            assertFalse(task.execute(need))
            assertFalse(task.execute(Decision.Allow(snapshot.copy(content = "changed"))))
            assertTrue(task.execute(allow))
            assertEquals(TaskState.EXECUTING, task.state)
            assertFalse(task.cancel())
            assertTrue(task.finish(successful))
            assertEquals(if (successful) TaskState.DONE else TaskState.FAILED, task.state)
            assertFalse(task.finish(true))
            assertFalse(task.cancel())
            assertEquals(if (successful) AuditDecision.DONE else AuditDecision.FAILED, events.last().decision)
        }
    }
    @Test fun cancelledPlannerResultIsIgnoredAndLogged() {
        for (afterPlan in listOf(false, true)) {
            val events = mutableListOf<AuditEvent>()
            val task = GuardTask(spec, events::add)
            if (afterPlan) task.plannerResult(need)
            assertTrue(task.cancel(screenLocked = afterPlan))
            assertEquals(TaskState.CANCELLED, task.state)
            assertFalse(task.plannerResult(allow))
            assertFalse(task.execute(allow))
            assertFalse(task.cancel())
            assertEquals(AuditReason.LATE_PLANNER_RESULT, events.last().reason)
            assertEquals(if (afterPlan) AuditReason.SCREEN_LOCKED else AuditReason.USER_CANCELLED, events.first().reason)
        }
    }
    @Test fun unconfirmedSafeActionAndDenial() {
        val task = GuardTask(spec, {})
        assertTrue(task.plannerResult(allow))
        assertTrue(task.execute(allow))
        assertTrue(task.finish(true))
        val denied = GuardTask(spec, {})
        assertTrue(denied.plannerResult(Decision.Deny(DenyReason.FORBIDDEN, "No")))
        assertEquals(TaskState.FAILED, denied.state)
        assertFalse(denied.execute(allow))
        assertFalse(denied.cancel())
        assertFalse(denied.plannerResult(need))
    }
}
