package com.handsfree.probes

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

class ProbeNotificationListener : NotificationListenerService() {
    private val notifications = mutableMapOf<String, StatusBarNotification>()
    private val controller get() = (application as ProbesApp).controller
    private fun consented() = controller.currentId in setOf("T5", "T6") && ACCESS in controller.consents
    private fun relevant(sbn: StatusBarNotification) = when (controller.currentId) {
        "T5" -> sbn.packageName == "com.whatsapp" && sbn.notification.category == Notification.CATEGORY_CALL
        "T6" -> sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        else -> false
    }
    fun clearNotifications() { notifications.clear() }
    fun refreshVisibleNotifications() {
        clearNotifications()
        if (!consented()) return
        activeNotifications.orEmpty().filter(::relevant).forEach { notifications[it.key] = it }
        controller.repo.emit(controller.currentId, "notification", "existing_visible_not_read", notifications.size.toString())
    }
    override fun onListenerConnected() { current = this; refreshVisibleNotifications() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!consented()) { clearNotifications(); return }
        if (sbn == null) return
        if (!relevant(sbn)) { notifications.remove(sbn.key); return }
        notifications[sbn.key] = sbn
        controller.repo.emit(controller.currentId, "notification", "posted", "package=${sbn.packageName};keyHash=${hash(sbn.key)}")
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { if (sbn != null) notifications.remove(sbn.key) }
    override fun onListenerDisconnected() { notifications.clear(); current = null }
    override fun onDestroy() { clearNotifications(); if (current === this) current = null; super.onDestroy() }
    fun describeCallButtons(): String {
        val call = latestCall() ?: return "No WhatsApp call notification"
        return call.notification.actions.orEmpty().mapIndexed { index, action -> "$index: ${action.title}" }.joinToString("; ")
    }
    private fun latestCall(): StatusBarNotification? {
        if (!consented()) { clearNotifications(); return null }
        return notifications.values.filter {
        it.packageName == "com.whatsapp" && it.notification.category == Notification.CATEGORY_CALL
    }.maxByOrNull { it.postTime }
    }
    fun sendCallAction(kind: String): Boolean {
        val call = latestCall() ?: return false
        val action = call.notification.actions.orEmpty().firstOrNull { it.title.toString().trim().equals(kind, true) } ?: return false
        val snapshot = call.key to call.postTime
        val pending = action.actionIntent ?: return false
        if (pending.creatorPackage != "com.whatsapp") return false
        val current = latestCall() ?: return false
        if (snapshot != (current.key to current.postTime) || current.notification.actions.orEmpty().none { it.actionIntent == pending && it.title == action.title }) return false
        try {
            pending.send()
            controller.repo.emit("T5", "pending_intent", "sent", "action=$kind;keyHash=${hash(call.key)};locked=${getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked};secure=${getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure}")
            return true
        } catch (_: PendingIntent.CanceledException) { controller.repo.emit("T5", "pending_intent", "cancelled", kind, "FAILED"); return false }
        catch (error: RuntimeException) { controller.repo.emit("T5", "pending_intent", "rejected", error.javaClass.simpleName, "FAILED"); return false }
    }
    fun mediaControllers(context: Context, packageName: String): List<MediaController> {
        if (!consented()) { clearNotifications(); return emptyList() }
        return notifications.values.filter { it.packageName == packageName }.mapNotNull { entry ->
            val notification = entry.notification
            val token = if (Build.VERSION.SDK_INT >= 33) notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
                else notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            token?.let { MediaController(context, it) }
        }.distinctBy { it.sessionToken }
    }
    companion object {
        const val ACCESS = "notification_access"
        var current: ProbeNotificationListener? = null
            private set
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
