package com.handsfree.guard

import java.util.Collections

enum class ParamType { TEXT, NUMBER, BOOLEAN }
enum class Sensitivity { NONE, CONFIRM, FORBIDDEN }
enum class ActionKind { SYSTEM_NO_DATA, PERSONAL_READ, SEND, CALL_OUT, NEEDS_LISTENING }
enum class Capability { NONE, CALL_PHONE, ANSWER_CALLS, SEND_SMS, READ_CALL_LOG, READ_CONTACTS, NOTIFICATION_ACCESS, LOCATION, CALENDAR_WRITE, MEDIA_SESSION }
enum class Origin { USER_UTTERANCE, PLANNER_FROM_USER, UNTRUSTED_CONTENT }
enum class DenyReason { FORBIDDEN, INVALID_TOOL, UNTRUSTED_ORIGIN, SYSTEM_PERMISSION, INTERNAL_CONSENT, ROLE_LOST, LOCKED, ACTIVE_CALL }

data class ToolSpec(val name: String, val params: Map<String, ParamType>, val permission: Capability, val sensitivity: Sensitivity, val kind: ActionKind) {
    init { require(name.matches(Regex("[a-z][a-z_]{0,63}"))) { "Tool names must be static identifiers" } }
}
data class ActionSnapshot(
    val tool: String,
    val kind: ActionKind,
    val recipientId: String? = null,
    val recipientLabel: String? = null,
    val recipientSaved: Boolean = false,
    val subscriptionId: Int? = null,
    val content: String? = null,
    val notificationKey: String? = null,
    val remoteInputActionId: String? = null,
    val conversationTarget: String? = null,
    val origins: Map<String, Origin> = emptyMap(),
) {
    fun frozen(): ActionSnapshot = copy(origins = Collections.unmodifiableMap(HashMap(origins)))
}
data class Context(
    val roleHeld: Boolean = true,
    val screenLocked: Boolean = false,
    val personalResultsOnLock: Boolean = false,
    val grantedCapabilities: Set<Capability> = emptySet(),
    val internalConsents: Set<Capability> = emptySet(),
    val inActiveCall: Boolean = false,
    val ringingCallerNameRequest: Boolean = false,
)
sealed interface Decision {
    data class Allow(val snapshot: ActionSnapshot) : Decision
    data class NeedConfirm(val prompt: String, val snapshot: ActionSnapshot) : Decision
    data class Deny(val reason: DenyReason, val spoken: String) : Decision
}
