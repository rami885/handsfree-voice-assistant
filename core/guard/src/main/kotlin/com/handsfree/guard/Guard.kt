package com.handsfree.guard

class Guard {
    private val forbiddenTools = setOf("purchase", "pay", "delete", "bulk_send", "change_security_settings")
    fun evaluate(spec: ToolSpec, action: ActionSnapshot, context: Context, confirmed: ActionSnapshot? = null): Decision {
        val snapshot = action.frozen()
        fun deny(reason: DenyReason, spoken: String) = Decision.Deny(reason, spoken)
        if (spec.sensitivity == Sensitivity.FORBIDDEN || spec.name in forbiddenTools) return deny(DenyReason.FORBIDDEN, "That action is not allowed")
        if (spec.name != snapshot.tool) return deny(DenyReason.INVALID_TOOL, "The requested tool has changed")
        val trusted = setOf(Origin.USER_UTTERANCE, Origin.PLANNER_FROM_USER)
        val recipientFields = listOfNotNull(
            snapshot.recipientId?.let { "recipientId" },
            snapshot.recipientLabel?.let { "recipientLabel" },
            snapshot.notificationKey?.let { "notificationKey" },
            snapshot.remoteInputActionId?.let { "remoteInputActionId" },
            snapshot.subscriptionId?.let { "subscriptionId" },
            "recipientSaved".takeIf { snapshot.recipientSaved },
            snapshot.conversationTarget?.let { "conversationTarget" },
        )
        if (snapshot.origins["tool"] !in trusted || recipientFields.any { snapshot.origins[it] !in trusted } ||
            ((snapshot.content != null || snapshot.kind == ActionKind.SEND) && snapshot.kind != ActionKind.PERSONAL_READ && snapshot.origins["content"] !in trusted)) {
            return deny(DenyReason.UNTRUSTED_ORIGIN, "I won't act on instructions inside a message")
        }
        val permission = spec.permission
        if (!context.roleHeld && permission in setOf(Capability.SEND_SMS, Capability.READ_CALL_LOG)) {
            return deny(DenyReason.ROLE_LOST, "The assistant role is required for this capability")
        }
        if (permission != Capability.NONE && permission !in context.grantedCapabilities) {
            return deny(DenyReason.SYSTEM_PERMISSION, "System permission is missing for ${permission.name}")
        }
        if (permission != Capability.NONE && permission !in context.internalConsents) {
            return deny(DenyReason.INTERNAL_CONSENT, "Your in-app consent is missing for ${permission.name}")
        }
        if (context.inActiveCall && snapshot.kind == ActionKind.NEEDS_LISTENING) {
            return deny(DenyReason.ACTIVE_CALL, "I can't listen during an active call")
        }
        val ringingName = context.ringingCallerNameRequest && snapshot.kind == ActionKind.PERSONAL_READ
        val savedCall = snapshot.kind == ActionKind.CALL_OUT && snapshot.recipientSaved
        if (context.screenLocked && !context.personalResultsOnLock && !ringingName && !savedCall &&
            snapshot.kind in setOf(ActionKind.PERSONAL_READ, ActionKind.SEND, ActionKind.CALL_OUT)) {
            return deny(DenyReason.LOCKED, "Unlock your phone or allow personal results on the lock screen")
        }
        val needsConfirmation = spec.sensitivity == Sensitivity.CONFIRM || snapshot.kind in setOf(ActionKind.CALL_OUT, ActionKind.SEND)
        if (needsConfirmation && snapshot != confirmed) {
            val recipient = snapshot.recipientLabel ?: snapshot.recipientId ?: "the selected recipient"
            val target = snapshot.conversationTarget?.let { " ($it)" } ?: ""
            val prompt = when (snapshot.kind) {
                ActionKind.CALL_OUT -> "Call $recipient$target?"
                ActionKind.SEND -> "Send ${snapshot.content ?: "the message"} to $recipient$target?"
                else -> "Confirm ${snapshot.tool.replace('_', ' ')}?"
            }
            return Decision.NeedConfirm(prompt, snapshot)
        }
        return Decision.Allow(snapshot)
    }
}
