package com.handsfree.probes

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.*
import android.media.MediaMetadata
import android.media.session.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import kotlinx.coroutines.*

class IntegrationProbes(private val context: Context, private val repo: ProbeRepository, private val configuration: () -> ProbeConfig) {
    private var mediaSession: MediaSession? = null
    private var buttonsArmed = false
    private var whatsappArmed = false
    private var generation = 0L
    private fun notificationAllowed(): Boolean = ProbeNotificationListener.ACCESS in configuration().consents &&
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, ProbeNotificationListener::class.java))
    fun whatsapp(kind: String? = null) {
        if (kind != null && !whatsappArmed) { repo.emit("T5", "notification", "start_required", outcome = "DENIED"); return }
        if (!notificationAllowed()) { repo.emit("T5", "notification", "access_or_consent_missing", outcome = "PERMISSION_DENIED"); return }
        val listener = ProbeNotificationListener.current
        if (listener == null) { repo.emit("T5", "notification", "listener_not_connected", outcome = "UNSUPPORTED"); return }
        repo.emit("T5", "notification", "app_version", repo.packageVersion("com.whatsapp"))
        if (kind == null) { whatsappArmed = true; repo.emit("T5", "notification", "call_buttons", listener.describeCallButtons()) }
        else if (!listener.sendCallAction(kind)) repo.emit("T5", "pending_intent", "unavailable_or_changed", kind, "UNSUPPORTED")
    }
    fun players(): List<String> = context.packageManager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
        .map { it.activityInfo.packageName }.distinct().sorted()
    suspend fun mediaSearch() {
        val config = configuration()
        val pkg = config.appPackage
        if (config.query.isBlank()) { repo.emit("T6", "query", "empty_query", outcome = "DENIED"); return }
        if (pkg.isBlank() || pkg !in players()) { repo.emit("T6", "player", "select_declared_player", outcome = "UNSUPPORTED"); return }
        repo.emit("T6", "player", "attempt", "package=$pkg;version=${repo.packageVersion(pkg)};coldDeclared=${config.mediaCold};method=${config.mediaMethod}")
        val controller = if (notificationAllowed()) ProbeNotificationListener.current?.mediaController(context, pkg) else null
        val activeGeneration = generation
        val received = CompletableDeferred<Unit>()
        val start = SystemClock.elapsedRealtime()
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (metadata == null || received.isCompleted || activeGeneration != generation) return
                val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                if (locked && !configuration().personalResultsOnLock) repo.emit("T6", "metadata", "redacted_locked", "elapsed=${SystemClock.elapsedRealtime() - start}", "DENIED")
                else {
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                    repo.emit("T6", "metadata", "first_after_request", "elapsed=${SystemClock.elapsedRealtime() - start};title=$title;titleContainsQuery=${title.contains(config.query, true)}")
                }
                received.complete(Unit)
            }
            override fun onSessionDestroyed() { repo.emit("T6", "session", "destroyed"); received.complete(Unit) }
        }
        controller?.registerCallback(callback, Handler(Looper.getMainLooper()))
        try {
            repo.emit("T6", "session", "availability", "present=${controller != null};baselineMetadata=${controller?.metadata != null}")
            if (config.mediaMethod == "controller") {
                if (controller == null || ((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) == 0L) {
                    repo.emit("T6", "session", "play_from_search_not_advertised", outcome = "UNSUPPORTED"); return
                }
                controller.transportControls.playFromSearch(config.query, null)
                repo.emit("T6", "request", "controller_play_from_search")
            } else {
                context.startActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtra(android.app.SearchManager.QUERY, config.query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                repo.emit("T6", "request", "package_explicit_intent")
            }
            if (controller == null) repo.emit("T6", "metadata", "no_exposed_session", outcome = "UNSUPPORTED")
            else if (withTimeoutOrNull(20_000) { received.await() } == null) repo.emit("T6", "metadata", "timeout_20_seconds", outcome = "FAILED")
        } finally { controller?.unregisterCallback(callback) }
    }
    fun startButtons() {
        stop(); buttonsArmed = true
        repo.emit("T11", "buttons", "mode", if (configuration().captureButtons) "active_session_changes_routing" else "passive_receiver")
        if (!configuration().captureButtons) return
        mediaSession = MediaSession(context, "probes-buttons").apply {
            setMediaButtonBroadcastReceiver(ComponentName(context, HeadsetReceiver::class.java))
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean { button(mediaButtonIntent, "session"); return false }
            }, Handler(Looper.getMainLooper()))
            isActive = true
        }
    }
    fun button(intent: Intent, source: String = "receiver") {
        if (!buttonsArmed) return
        val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        repo.emit("T11", "buttons", "key", "source=$source;code=${key.keyCode};action=${key.action};repeat=${key.repeatCount};long=${key.isLongPress};duration=${key.eventTime - key.downTime};captureSession=${configuration().captureButtons}")
    }
    fun stop() { generation++; whatsappArmed = false; mediaSession?.release(); mediaSession = null; buttonsArmed = false }
}
class HeadsetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MEDIA_BUTTON) (context.applicationContext as ProbesApp).controller.integrations.button(intent)
    }
}
