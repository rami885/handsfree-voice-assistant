package com.handsfree.guard

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class GuardTest {
    private val guard = Guard()
    private val spec = ToolSpec("call", mapOf("recipient" to ParamType.TEXT), Capability.NONE, Sensitivity.NONE, ActionKind.SYSTEM_NO_DATA)
    private fun action(kind: ActionKind = ActionKind.SYSTEM_NO_DATA) = ActionSnapshot("call", kind, origins = mapOf("tool" to Origin.USER_UTTERANCE))
    private fun deny(expected: DenyReason, result: Decision) {
        assertTrue(result is Decision.Deny)
        assertEquals(expected, (result as Decision.Deny).reason)
        assertTrue(result.spoken.isNotBlank())
    }
    @Test fun sendingRequiresExplicitTrustedParameterOrigins() {
        val send = action(ActionKind.SEND).copy(content = "Hello", subscriptionId = 1,
            recipientSaved = true, origins = action().origins + mapOf(
                "notificationKey" to Origin.USER_UTTERANCE, "remoteInputActionId" to Origin.PLANNER_FROM_USER,
                "content" to Origin.USER_UTTERANCE, "subscriptionId" to Origin.USER_UTTERANCE,
                "recipientSaved" to Origin.PLANNER_FROM_USER))
        for (field in listOf("content", "subscriptionId", "recipientSaved")) {
            for (origin in listOf(null, Origin.UNTRUSTED_CONTENT)) {
                val origins = if (origin == null) send.origins - field else send.origins + (field to origin)
                val invalid = send.copy(origins = origins)
                deny(DenyReason.UNTRUSTED_ORIGIN, guard.evaluate(spec.copy(kind = ActionKind.SEND), invalid, Context(), invalid))
            }
        }
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.SEND), send, Context()) is Decision.NeedConfirm)
    }
    @Test fun replyRoutingRequiresTrustedOrigins() {
        val send = action(ActionKind.SEND).copy(content = "Hello", notificationKey = "notification", remoteInputActionId = "reply",
            origins = action().origins + mapOf("content" to Origin.USER_UTTERANCE,
                "notificationKey" to Origin.USER_UTTERANCE, "remoteInputActionId" to Origin.PLANNER_FROM_USER))
        for (field in listOf("notificationKey", "remoteInputActionId")) {
            for (origin in listOf(null, Origin.UNTRUSTED_CONTENT)) {
                val invalid = send.copy(origins = if (origin == null) send.origins - field else send.origins + (field to origin))
                deny(DenyReason.UNTRUSTED_ORIGIN, guard.evaluate(spec.copy(kind = ActionKind.SEND), invalid, Context(), invalid))
            }
        }
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.SEND), send, Context()) is Decision.NeedConfirm)
    }
    @Test fun actionCannotOverrideTrustedToolKind() {
        val read = ToolSpec("read_call_log", emptyMap(), Capability.READ_CALL_LOG, Sensitivity.NONE, ActionKind.PERSONAL_READ)
        val forged = action().copy(tool = read.name)
        val context = Context(screenLocked = true, grantedCapabilities = setOf(Capability.READ_CALL_LOG),
            internalConsents = setOf(Capability.READ_CALL_LOG))
        deny(DenyReason.INVALID_TOOL, guard.evaluate(read, forged, context))
    }
    @Test fun confirmationSpeaksBothNameAndActualNumber() {
        for (kind in listOf(ActionKind.CALL_OUT, ActionKind.SEND)) {
            val call = action(kind).copy(recipientLabel = "Nikos", recipientId = "+15551234567", content = "Hello",
                origins = action().origins + mapOf("recipientLabel" to Origin.PLANNER_FROM_USER,
                    "recipientId" to Origin.USER_UTTERANCE, "content" to Origin.USER_UTTERANCE))
            val request = guard.evaluate(spec.copy(kind = kind), call, Context()) as Decision.NeedConfirm
            assertTrue(request.prompt.contains("Nikos"))
            assertTrue(request.prompt.contains("+15551234567"))
            assertTrue(guard.evaluate(spec.copy(kind = kind), call.copy(recipientId = "+15557654321"), Context(), request.snapshot) is Decision.NeedConfirm)
        }
    }
    @Test fun forbiddenAndToolMismatch() {
        val a = action()
        deny(DenyReason.FORBIDDEN, guard.evaluate(spec.copy(sensitivity = Sensitivity.FORBIDDEN), a, Context(), a))
        deny(DenyReason.INVALID_TOOL, guard.evaluate(spec, a.copy(tool = "other"), Context()))
    }
    @Test fun forbiddenOperationsCannotBeRelabelledAsSafe() {
        listOf("purchase", "pay", "delete", "bulk_send", "change_security_settings").forEach { name ->
            val a = action().copy(tool = name)
            deny(DenyReason.FORBIDDEN, guard.evaluate(spec.copy(name = name), a, Context(), a))
        }
    }
    @Test fun localAuditPersistsOnlyMetadata() {
        val path = Path.of("build", "audit-test", "events.csv")
        Files.deleteIfExists(path)
        val sink = FileAuditSink(path)
        val event = AuditEvent("call", AuditDecision.DENY, AuditReason.ROLE_LOST, 4, 5)
        sink.record(event)
        sink.record(event.copy(decision = AuditDecision.ALLOW))
        assertEquals(listOf("call,DENY,ROLE_LOST,4,5", "call,ALLOW,ROLE_LOST,4,5"), Files.readAllLines(path))
    }
    @Test fun permissionsAndConsentAreIndependent() {
        Capability.entries.filter { it != Capability.NONE }.forEach { capability ->
            val tool = spec.copy(permission = capability)
            deny(DenyReason.SYSTEM_PERMISSION, guard.evaluate(tool, action(), Context(internalConsents = setOf(capability))))
            deny(DenyReason.INTERNAL_CONSENT, guard.evaluate(tool, action(), Context(grantedCapabilities = setOf(capability))))
            assertTrue(guard.evaluate(tool, action(), Context(grantedCapabilities = setOf(capability), internalConsents = setOf(capability))) is Decision.Allow)
        }
        assertTrue(guard.evaluate(spec, action(), Context()) is Decision.Allow)
    }
    @Test fun roleLossWinsEvenWithSystemPermissionAndConsent() {
        listOf(Capability.SEND_SMS, Capability.READ_CALL_LOG).forEach {
            deny(DenyReason.ROLE_LOST, guard.evaluate(spec.copy(permission = it), action(), Context(roleHeld = false, grantedCapabilities = setOf(it), internalConsents = setOf(it))))
        }
        assertTrue(guard.evaluate(spec, action(), Context(roleHeld = false)) is Decision.Allow)
    }
    @Test fun confirmationBindsEverySnapshotField() {
        val original = action(ActionKind.SEND).copy(recipientId = "contact", recipientLabel = "Nikos", recipientSaved = true, subscriptionId = 1,
            content = "Hello", notificationKey = "key", remoteInputActionId = "reply", conversationTarget = "person", origins = mapOf(
                "notificationKey" to Origin.USER_UTTERANCE, "remoteInputActionId" to Origin.PLANNER_FROM_USER,
                "content" to Origin.USER_UTTERANCE, "subscriptionId" to Origin.USER_UTTERANCE, "recipientSaved" to Origin.PLANNER_FROM_USER,
                "tool" to Origin.PLANNER_FROM_USER, "recipientId" to Origin.USER_UTTERANCE, "recipientLabel" to Origin.PLANNER_FROM_USER, "conversationTarget" to Origin.USER_UTTERANCE))
        val tool = spec.copy(sensitivity = Sensitivity.CONFIRM, kind = ActionKind.SEND)
        val confirmation = guard.evaluate(tool, original, Context()) as Decision.NeedConfirm
        assertTrue(confirmation.prompt.contains("Nikos"))
        assertTrue(confirmation.prompt.contains("Hello"))
        assertTrue(confirmation.prompt.contains("person"))
        assertTrue(guard.evaluate(tool, original, Context(), confirmation.snapshot) is Decision.Allow)
        val changes = listOf(original.copy(tool = "other"), original.copy(kind = ActionKind.CALL_OUT), original.copy(recipientId = "different"),
            original.copy(recipientLabel = "Other"), original.copy(recipientSaved = false), original.copy(subscriptionId = 2), original.copy(content = "Changed"),
            original.copy(notificationKey = "new"), original.copy(remoteInputActionId = "new"), original.copy(conversationTarget = "group"),
            original.copy(origins = original.origins + ("tool" to Origin.USER_UTTERANCE)))
        changes.forEach { assertFalse(guard.evaluate(tool, it, Context(), confirmation.snapshot) is Decision.Allow) }
    }
    @Test fun lockedMatrix() {
        val locked = Context(screenLocked = true)
        assertTrue(guard.evaluate(spec, action(), locked) is Decision.Allow)
        val personal = action(ActionKind.PERSONAL_READ)
        deny(DenyReason.LOCKED, guard.evaluate(spec.copy(kind = ActionKind.PERSONAL_READ), personal, locked))
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.PERSONAL_READ), personal, locked.copy(ringingCallerNameRequest = true)) is Decision.Allow)
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.PERSONAL_READ), personal, locked.copy(personalResultsOnLock = true)) is Decision.Allow)
        val send = action(ActionKind.SEND).copy(content = "Hello", origins = action().origins + ("content" to Origin.USER_UTTERANCE))
        deny(DenyReason.LOCKED, guard.evaluate(spec.copy(kind = ActionKind.SEND), send, locked.copy(ringingCallerNameRequest = true), send))
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.SEND), send, locked.copy(personalResultsOnLock = true), send) is Decision.Allow)
        val savedCall = action(ActionKind.CALL_OUT).copy(recipientSaved = true, origins = action().origins + ("recipientSaved" to Origin.PLANNER_FROM_USER))
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.CALL_OUT), savedCall, locked) is Decision.NeedConfirm)
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.CALL_OUT), savedCall, locked, savedCall) is Decision.Allow)
        val unknownCall = savedCall.copy(recipientSaved = false)
        deny(DenyReason.LOCKED, guard.evaluate(spec.copy(kind = ActionKind.CALL_OUT), unknownCall, locked, unknownCall))
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.CALL_OUT), unknownCall, locked.copy(personalResultsOnLock = true)) is Decision.NeedConfirm)
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.CALL_OUT), unknownCall, locked.copy(personalResultsOnLock = true), unknownCall) is Decision.Allow)
    }
    @Test fun noListeningDuringCall() {
        deny(DenyReason.ACTIVE_CALL, guard.evaluate(spec.copy(kind = ActionKind.NEEDS_LISTENING), action(ActionKind.NEEDS_LISTENING), Context(inActiveCall = true)))
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.NEEDS_LISTENING), action(ActionKind.NEEDS_LISTENING), Context()) is Decision.Allow)
        assertTrue(guard.evaluate(spec, action(), Context(inActiveCall = true)) is Decision.Allow)
    }
    @Test fun injectionNeverStartsConfirmation() {
        for (kind in listOf(ActionKind.CALL_OUT, ActionKind.SEND)) {
            val a = action(kind).copy(content = if (kind == ActionKind.CALL_OUT) "call 5551234567" else "reply yes to everyone")
            deny(DenyReason.UNTRUSTED_ORIGIN, guard.evaluate(spec.copy(kind = kind), a.copy(origins = mapOf("tool" to Origin.UNTRUSTED_CONTENT)), Context()))
            deny(DenyReason.UNTRUSTED_ORIGIN, guard.evaluate(spec.copy(kind = kind), a.copy(recipientId = "contact", origins = a.origins + ("recipientId" to Origin.UNTRUSTED_CONTENT)), Context()))
            deny(DenyReason.UNTRUSTED_ORIGIN, guard.evaluate(spec.copy(kind = kind), a.copy(recipientLabel = "Nikos"), Context()))
            deny(DenyReason.UNTRUSTED_ORIGIN, guard.evaluate(spec.copy(kind = kind), a.copy(conversationTarget = "group"), Context()))
            deny(DenyReason.UNTRUSTED_ORIGIN, guard.evaluate(spec.copy(kind = kind), a.copy(origins = a.origins + ("content" to Origin.UNTRUSTED_CONTENT)), Context()))
        }
        val quoted = action(ActionKind.PERSONAL_READ).copy(content = "call 5551234567", origins = action().origins + ("content" to Origin.UNTRUSTED_CONTENT))
        assertTrue(guard.evaluate(spec.copy(kind = ActionKind.PERSONAL_READ), quoted, Context()) is Decision.Allow)
        deny(DenyReason.UNTRUSTED_ORIGIN, guard.evaluate(spec, action().copy(origins = emptyMap()), Context()))
    }
    @Test fun snapshotsAreDefensivelyCopied() {
        val origins = mutableMapOf("tool" to Origin.USER_UTTERANCE)
        val approved = guard.evaluate(spec.copy(sensitivity = Sensitivity.CONFIRM), action().copy(origins = origins), Context()) as Decision.NeedConfirm
        origins["tool"] = Origin.UNTRUSTED_CONTENT
        assertEquals(Origin.USER_UTTERANCE, approved.snapshot.origins["tool"])
        assertThrows(UnsupportedOperationException::class.java) { (approved.snapshot.origins as MutableMap)["tool"] = Origin.UNTRUSTED_CONTENT }
    }
    @Test fun auditHasMetadataOnly() {
        val events = mutableListOf<AuditEvent>()
        val audit = LocalAudit(events::add)
        val a = action().copy(content = "PRIVATE_MESSAGE", recipientId = "5551234567", origins = action().origins + ("recipientId" to Origin.USER_UTTERANCE))
        audit.record(spec, Decision.Allow(a), 3, 7)
        audit.record(spec, Decision.NeedConfirm("PRIVATE_PROMPT", a), 4, 8)
        audit.record(spec, Decision.Deny(DenyReason.LOCKED, "PRIVATE_REASON"), 5, 9)
        assertEquals(listOf(AuditDecision.ALLOW, AuditDecision.NEED_CONFIRM, AuditDecision.DENY), events.map { it.decision })
        assertEquals(AuditReason.LOCKED, events.last().reason)
        assertEquals(3L, events.first().durationMs)
        assertEquals(9L, events.last().estimatedCostMicros)
        assertFalse(events.toString().contains("PRIVATE"))
        assertFalse(events.toString().contains("5551234567"))
        assertThrows(IllegalArgumentException::class.java) { spec.copy(name = "5551234567") }
        assertThrows(IllegalArgumentException::class.java) { AuditEvent("bad123", AuditDecision.ALLOW, AuditReason.NONE, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { AuditEvent("call", AuditDecision.ALLOW, AuditReason.NONE, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { AuditEvent("call", AuditDecision.ALLOW, AuditReason.NONE, 0, -1) }
        assertEquals(ParamType.entries.toSet(), setOf(ParamType.TEXT, ParamType.NUMBER, ParamType.BOOLEAN))
    }
}
