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
import org.json.JSONObject
import java.util.Locale

class IntegrationProbes(private val context: Context, private val repo: ProbeRepository, private val consents: ProbeConsentStore, private val configuration: () -> ProbeConfig) {
    companion object {
        const val MEDIA_SEARCH_WINDOW_MS = 4_000L
        internal fun matchesQuery(query: String, title: String, artist: String): Boolean {
            fun tokens(value: String) = Regex("[\\p{L}\\p{N}]+").findAll(value.lowercase(Locale.ROOT)).map { it.value }.toSet()
            val terms = tokens(query)
            return terms.isNotEmpty() && (tokens(title).containsAll(terms) || tokens(artist).containsAll(terms))
        }
    }
    private var mediaSession: MediaSession? = null
    private var buttonsArmed = false
    private var whatsappArmed = false
    private var generation = 0L
    private fun notificationAllowed(): Boolean = ProbeNotificationListener.ACCESS in consents &&
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
        val requestSent = CompletableDeferred<Unit>()
        val observed = linkedMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        data class Evidence(var playing: Boolean = false, var matched: Boolean = false)
        val evidence = mutableMapOf<MediaSession.Token, Evidence>()
        var start = SystemClock.elapsedRealtime()
        var requested = false
        var requestedToken: MediaSession.Token? = null
        var listening = false
        var notificationWatcher: Job? = null
        val discoverySources = linkedMapOf<MediaSession.Token, MutableSet<String>>()
        fun eligible() = activeGeneration == generation && notificationAllowed()
        fun evaluate(token: MediaSession.Token) {
            if (!requested || token != requestedToken || !eligible() || received.isCompleted) return
            if (SystemClock.elapsedRealtime() - start >= MEDIA_SEARCH_WINDOW_MS) return
            val result = evidence.getValue(token)
            if (result.playing && result.matched) received.complete(Unit)
        }
        fun metadata(token: MediaSession.Token, metadata: MediaMetadata?) {
            if (metadata == null || !requested || token != requestedToken || !eligible() || received.isCompleted) return
            val result = evidence.getValue(token)
            result.matched = matchesQuery(config.query, metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            // Export matching evidence, never the personal title/artist above a locked screen.
            val fields = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - start).put("matched", result.matched)
                .put("redacted", locked && !configuration().personalResultsOnLock)
            if (!locked || configuration().personalResultsOnLock) {
                fields.put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
                    .put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            }
            repo.emit("T6", "metadata", "match_observed", fields.toString())
            evaluate(token)
        }
        fun requestController(controller: MediaController) {
            if (requested || config.mediaMethod != "controller" || !eligible()) return
            if (((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) == 0L) return
            requestedToken = controller.sessionToken
            evidence.getValue(controller.sessionToken).playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            start = SystemClock.elapsedRealtime(); requested = true
            controller.transportControls.playFromSearch(config.query, null)
            requestSent.complete(Unit)
            repo.emit("T6", "request", "controller_play_from_search")
        }
        fun observe(controller: MediaController, source: String) {
            if (!eligible() || controller.packageName != pkg) return
            val token = controller.sessionToken
            val sources = discoverySources.getOrPut(token) { mutableSetOf() }
            if (sources.add(source)) repo.emit("T6", "session", "discovered", "session=${discoverySources.keys.indexOf(token) + 1};source=$source;package=$pkg")
            if (token in observed) return
            evidence[token] = Evidence()
            val bindColdSession = requested && config.mediaMethod != "controller" && requestedToken == null
            if (bindColdSession) requestedToken = token
            repo.emit("T6", "metadata", "baseline_at_discovery", "session=${discoverySources.keys.indexOf(token) + 1};present=${controller.metadata != null}")
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(value: MediaMetadata?) { metadata(token, value) }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (!eligible()) return
                    requestController(controller)
                    if (requested && token == requestedToken) {
                        evidence.getValue(token).playing = state?.state == PlaybackState.STATE_PLAYING
                        evaluate(token)
                    }
                }
                override fun onSessionDestroyed() {
                    if (eligible()) {
                        evidence.getValue(token).playing = false
                        repo.emit("T6", "session", "destroyed")
                    }
                }
            }
            controller.registerCallback(callback, Handler(Looper.getMainLooper()))
            observed[token] = controller to callback
            requestController(controller)
            // A cold session first discovered after the intent may have published both values already.
            if (bindColdSession) {
                evidence.getValue(token).playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                metadata(token, controller.metadata)
            }
        }
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> controllers.orEmpty().forEach { observe(it, "active_sessions") } }
        try {
            if (notificationAllowed()) {
                try {
                    manager.addOnActiveSessionsChangedListener(changed, component, Handler(Looper.getMainLooper()))
                    listening = true
                    manager.getActiveSessions(component).forEach { observe(it, "active_sessions") }
                } catch (_: SecurityException) { repo.emit("T6", "session", "active_sessions_denied", outcome = "PERMISSION_DENIED") }
                notificationWatcher = CoroutineScope(currentCoroutineContext()).launch {
                    while (isActive && eligible()) {
                        ProbeNotificationListener.current?.mediaControllers(context, pkg).orEmpty().forEach { observe(it, "notification_token") }
                        delay(100)
                    }
                }
            }
            repo.emit("T6", "session", "availability", "present=${observed.isNotEmpty()};watching=$listening")
            if (config.mediaMethod != "controller") {
                requestedToken = observed.keys.firstOrNull()
                requestedToken?.let { token -> evidence.getValue(token).playing = observed.getValue(token).first.playbackState?.state == PlaybackState.STATE_PLAYING }
                start = SystemClock.elapsedRealtime(); requested = true
                requestSent.complete(Unit)
                context.startActivity(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtra(android.app.SearchManager.QUERY, config.query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                repo.emit("T6", "request", "package_explicit_intent")
            }
            if (!notificationAllowed()) { repo.emit("T6", "metadata", "access_or_consent_missing", outcome = "PERMISSION_DENIED"); return }
            // Controller discovery has its own bounded wait; the measurement starts at the actual request.
            if (!requested) withTimeoutOrNull(MEDIA_SEARCH_WINDOW_MS) { requestSent.await() }
            val success = requested && withTimeoutOrNull((MEDIA_SEARCH_WINDOW_MS - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0)) { received.await(); true } == true
            val result = requestedToken?.let { evidence[it] }
            val outcome = when {
                success -> "success"
                observed.isEmpty() -> "no_session"
                !requested -> "unsupported"
                result?.playing == true -> "playing_no_match"
                else -> "timeout_no_playing"
            }
            repo.emit("T6", "measurement", "result", JSONObject().put("result", outcome).put("windowMs", MEDIA_SEARCH_WINDOW_MS)
                .put("elapsedMs", if (requested) SystemClock.elapsedRealtime() - start else JSONObject.NULL)
                .put("requested", requested).put("playing", result?.playing ?: false).put("matched", result?.matched ?: false)
                .put("session", requestedToken?.let { discoverySources.keys.indexOf(it) + 1 } ?: JSONObject.NULL).toString(),
                if (success) "PASS" else if (outcome in setOf("unsupported", "no_session")) "UNSUPPORTED" else "FAIL")
        } finally {
            notificationWatcher?.cancel()
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
