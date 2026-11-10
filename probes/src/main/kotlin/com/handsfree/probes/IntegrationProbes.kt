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
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(context, ProbeNotificationListener::class.java)
        val activeGeneration = generation
        val received = CompletableDeferred<Unit>()
        val observed = linkedMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        var start = SystemClock.elapsedRealtime()
        var requested = false
        var listening = false
        fun eligible() = activeGeneration == generation && notificationAllowed()
        fun metadata(metadata: MediaMetadata?) {
            if (metadata == null || !requested || received.isCompleted || !eligible()) return
            val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (locked && !configuration().personalResultsOnLock) repo.emit("T6", "metadata", "redacted_locked", "elapsed=${SystemClock.elapsedRealtime() - start}", "DENIED")
            else {
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                repo.emit("T6", "metadata", "first_after_request", "elapsed=${SystemClock.elapsedRealtime() - start};title=$title;titleContainsQuery=${title.contains(config.query, true)}")
            }
            received.complete(Unit)
        }
        fun requestController(controller: MediaController) {
            if (requested || config.mediaMethod != "controller" || !eligible()) return
            if (((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) == 0L) return
            start = SystemClock.elapsedRealtime(); requested = true
            controller.transportControls.playFromSearch(config.query, null)
            repo.emit("T6", "request", "controller_play_from_search")
        }
        fun observe(controller: MediaController) {
            if (!eligible() || controller.packageName != pkg || controller.sessionToken in observed) return
            val afterRequest = requested
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(value: MediaMetadata?) { metadata(value) }
                override fun onPlaybackStateChanged(state: PlaybackState?) { requestController(controller) }
                override fun onSessionDestroyed() { if (eligible()) repo.emit("T6", "session", "destroyed") }
            }
            controller.registerCallback(callback, Handler(Looper.getMainLooper()))
            observed[controller.sessionToken] = controller to callback
            requestController(controller)
            if (afterRequest) metadata(controller.metadata)
        }
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> controllers.orEmpty().forEach(::observe) }
        try {
            if (notificationAllowed()) {
                try {
                    manager.addOnActiveSessionsChangedListener(changed, component, Handler(Looper.getMainLooper()))
                    listening = true
                    manager.getActiveSessions(component).forEach(::observe)
                } catch (_: SecurityException) { repo.emit("T6", "session", "active_sessions_denied", outcome = "PERMISSION_DENIED") }
                ProbeNotificationListener.current?.mediaController(context, pkg)?.let(::observe)
            }
            repo.emit("T6", "session", "availability", "present=${observed.isNotEmpty()};watching=$listening")
            if (config.mediaMethod != "controller") {
                start = SystemClock.elapsedRealtime(); requested = true
                context.startActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtra(android.app.SearchManager.QUERY, config.query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                repo.emit("T6", "request", "package_explicit_intent")
            }
            if (!notificationAllowed()) { repo.emit("T6", "metadata", "access_or_consent_missing", outcome = "PERMISSION_DENIED"); return }
            if (withTimeoutOrNull(20_000) { received.await() } == null) {
                repo.emit("T6", "metadata", if (config.mediaMethod == "controller" && !requested) "play_from_search_not_advertised" else "timeout_20_seconds", outcome = "FAILED")
            }
        } finally {
            if (listening) manager.removeOnActiveSessionsChangedListener(changed)
            observed.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        }
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
