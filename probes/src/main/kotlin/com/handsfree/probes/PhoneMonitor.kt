package com.handsfree.probes

import android.Manifest.permission.*
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telephony.*
import kotlinx.coroutines.flow.MutableStateFlow

class PhoneMonitor(private val context: Context, private val repo: ProbeRepository, private val interrupt: () -> Unit,
    private val stateChanged: (Int, Int?, Int) -> Unit = { _, _, _ -> },
) {
    val states = MutableStateFlow<Map<Int, Int?>>(emptyMap())
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val callbacks = mutableListOf<Pair<TelephonyManager, TelephonyCallback>>()
    private val legacy = mutableListOf<Pair<TelephonyManager, PhoneStateListener>>()
    private var experiment = "T1b"
    private var snapshotKnown = false
    private var legacyRequested = false
    private var subscribed = false
    internal val isMonitoring: Boolean get() = subscribed
    private val changed = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() { rebuild() }
    }
    fun start(id: String, legacyNumber: Boolean = false) {
        experiment = id; legacyRequested = legacyNumber
        if (context.checkSelfPermission(READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            repo.emit(id, "phone", "permission_denied", outcome = "PERMISSION_DENIED"); return
        }
        if (!subscribed) { subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, changed); subscribed = true }
        rebuild()
    }
    private fun rebuild() {
        unregister()
        if (context.checkSelfPermission(READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) { snapshotKnown = false; return }
        try {
            val active = subscriptions.activeSubscriptionInfoList.orEmpty()
            states.value = active.associate { it.subscriptionId to null }
            snapshotKnown = true
            repo.emit(experiment, "subscriptions", "active", active.joinToString { it.subscriptionId.toString() })
            active.forEach { info ->
                val manager = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(info.subscriptionId)
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        val wasRinging = isRinging()
                        val previous = states.value[info.subscriptionId]
                        states.value = states.value + (info.subscriptionId to state)
                        stateChanged(info.subscriptionId, previous, state)
                        if (state == TelephonyManager.CALL_STATE_OFFHOOK || (wasRinging && !isRinging())) interrupt()
                        repo.emit(experiment, "phone", "state", "subscription=${info.subscriptionId};state=$state")
                    }
                }
                manager.registerTelephonyCallback(context.mainExecutor, callback)
                callbacks += manager to callback
                if (legacyRequested) registerLegacy(manager, info.subscriptionId)
            }
        } catch (_: SecurityException) { snapshotKnown = false; repo.emit(experiment, "phone", "permission_denied", outcome = "PERMISSION_DENIED") }
    }
    private fun registerLegacy(manager: TelephonyManager, subscriptionId: Int) {
        if (!context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            repo.emit("T1c", "legacy", "role_missing", outcome = "DENIED"); return
        }
        if (context.checkSelfPermission(READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            repo.emit("T1c", "legacy", "permission_denied", outcome = "PERMISSION_DENIED"); return
        }
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (!context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)) { stopLegacy(); return }
                repo.emit("T1c", "legacy", "number_present", "subscription=$subscriptionId;state=$state;present=${!phoneNumber.isNullOrBlank()}")
            }
        }
        manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacy += manager to listener
    }
    fun safeToListen(): Boolean = snapshotKnown && states.value.values.all { it != null && it != TelephonyManager.CALL_STATE_OFFHOOK } &&
        audio.mode != AudioManager.MODE_IN_CALL && audio.mode != AudioManager.MODE_IN_COMMUNICATION
    fun isRinging(): Boolean = states.value.values.any { it == TelephonyManager.CALL_STATE_RINGING }
    fun exactlyOneRinging(): Boolean = states.value.values.count { it == TelephonyManager.CALL_STATE_RINGING } == 1 &&
        states.value.values.all { it == TelephonyManager.CALL_STATE_RINGING || it == TelephonyManager.CALL_STATE_IDLE }
    fun stopLegacy() {
        legacy.forEach { (manager, listener) -> try { manager.listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: SecurityException) { } }
        legacy.clear()
    }
    private fun unregister() {
        callbacks.forEach { (manager, callback) -> try { manager.unregisterTelephonyCallback(callback) } catch (_: SecurityException) { } }
        callbacks.clear(); stopLegacy()
    }
    fun stop() {
        unregister()
        if (subscribed) subscriptions.removeOnSubscriptionsChangedListener(changed)
        subscribed = false; snapshotKnown = false; states.value = emptyMap()
    }
}
