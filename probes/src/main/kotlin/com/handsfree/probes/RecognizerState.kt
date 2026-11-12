package com.handsfree.probes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognitionService
import org.json.JSONArray

/** A settings snapshot, not proof that a particular recognition request reached the proxy. */
internal object RecognizerState {
    fun record(
        context: Context,
        repo: ProbeRepository,
        readSetting: () -> String? = { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") },
    ) {
        val value = try { readSetting() ?: "unset" } catch (_: SecurityException) { "unreadable" }
        val component = ComponentName.unflattenFromString(value)
        val proxy = ComponentName(context, ProxyRecognitionService::class.java)
        repo.emit("T1a", "recognition", "voice_recognition_service", value)
        repo.emit("T1a", "recognition", "default_is_proxy",
            if (value == "unset" || value == "unreadable") "unknown" else (component == proxy).toString())
        try {
            val visible = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.let { service -> ComponentName(service.packageName, service.name).flattenToString() } }
                .distinct().sorted()
            repo.emit("T1a", "recognition", "visible_services", JSONArray(visible).toString())
        } catch (_: SecurityException) {
            repo.emit("T1a", "recognition", "visible_services", "unreadable")
        }
        repo.emit("T1a", "recognition", "interpretation", "manual_validation_required", "MANUAL")
    }
}
