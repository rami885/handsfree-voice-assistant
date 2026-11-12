package com.handsfree.probes

import android.content.Context
import android.media.*
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class ProbeSession(private val serviceContext: Context) : VoiceInteractionSession(serviceContext) {
    private val controller get() = (serviceContext.applicationContext as ProbesApp).controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var focus: AudioFocusRequest? = null
    private var focusJob: Job? = null
    private var content: View? = null
    private var actions: LinearLayout? = null
    private var preparedRequest: Bundle? = null
    internal var experimentId: String? = null
        private set
    internal var requestGeneration: Long? = null
        private set
    internal fun bindExperiment(id: String, generation: Long) {
        experimentId = id
        requestGeneration = generation
    }
    override fun onCreateContentView(): View {
        val dark = serviceContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val foreground = if (dark) Color.WHITE else Color.BLACK
        val background = if (dark) Color.rgb(18, 18, 18) else Color.WHITE
        val body = LinearLayout(serviceContext).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        body.addView(TextView(serviceContext).apply { text = "MoVA Lab assistant session — screen-off / lock tests"; setTextColor(foreground) })
        actions = LinearLayout(serviceContext).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        experimentId?.let(::refreshActions)
        body.addView(Button(serviceContext).apply { text = "Stop"; setOnClickListener {
            if (controller.sessionMatches(this@ProbeSession)) { controller.stop("session_stop"); hide() }
        } })
        val text = TextView(serviceContext).apply { setTextColor(foreground) }; body.addView(text)
        scope.launch { combine(controller.repo.lines, controller.repo.spoken) { lines, spoken ->
            val locked = serviceContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            lines.takeLast(12).joinToString("\n") { if (locked) it.substringBefore(":") else it } + "\n" + spoken
        }.collect { text.text = it } }
        return ScrollView(serviceContext).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
            setBackgroundColor(background)
            addView(body)
            content = this
        }
    }
    internal fun dismissForScreenChange() {
        clearSessionUi()
        hide()
    }
    private fun clearSessionUi() {
        focusJob?.cancel()
        focusJob = null
        focus?.let(serviceContext.getSystemService(AudioManager::class.java)::abandonAudioFocusRequest)
        focus = null
        actions?.removeAllViews()
    }
    private fun rejectShow() {
        // A late callback must not disable an already accepted show on this instance.
        if (controller.sessionMatches(this)) return
        clearSessionUi()
        experimentId = null
        requestGeneration = null
        if (controller.session === this) controller.session = null
        // hide() also removes pending MSG_SHOW, including a newer valid request.
        if (!controller.hasPendingSessionRequest) hide()
    }
    private fun refreshActions(id: String) {
        val buttons = actions ?: return
        buttons.removeAllViews()
        val kinds = when (id) {
            "T5" -> listOf("Answer", "Decline")
            "T10" -> listOf("call", "navigation", "timer", "read", "send")
            "T2" -> listOf("call", "play", "navigation", "timer", "app")
            else -> emptyList()
        }
        kinds.forEach { kind ->
            buttons.addView(Button(serviceContext).apply {
                text = if (id == "T5") "WhatsApp $kind" else kind
                setOnClickListener {
                    if (experimentId == id && controller.sessionMatches(this@ProbeSession)) {
                        if (id == "T5") controller.integrations.whatsapp(kind) else controller.launchFromSession(kind, this@ProbeSession)
                    }
                }
            })
        }
        if (id == "T7") buttons.addView(Button(serviceContext).apply { text = "Request focus (T7)"; setOnClickListener { requestFocus(id) } })
    }
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // System invocations have no app token. Snapshot selection in this session's
        // local prepare callback; mutations to the service's remote Bundle do not return.
        preparedRequest = if (args?.containsKey("session_generation") == true || args?.containsKey("experiment") == true)
            Bundle(args) else controller.newSessionRequest(controller.currentId)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val request = if (args?.containsKey("experiment") == true) args else preparedRequest
        preparedRequest = null
        val id = request?.getString("experiment")
        if (id == null) { rejectShow(); return }
        val generation = request.getLong("session_generation", -1L)
        if (!controller.sessionShown(id, generation, this)) {
            rejectShow()
            return
        }
        refreshActions(id)
        focusJob?.cancel()
        if (id == "T7" || id == "T1b") {
            focusJob = scope.launch {
                val ready = withTimeoutOrNull(2_000) { while (content?.hasWindowFocus() != true) delay(20); true }
                if (ready == true && controller.sessionMatches(this@ProbeSession)) requestFocus(id)
                else controller.repo.emit(id, "focus", "session_window_not_focused", outcome = "UNSUPPORTED")
            }
        }
    }
    fun requestFocus(id: String) {
        if (experimentId != id || !controller.sessionMatches(this)) return
        val manager = serviceContext.getSystemService(AudioManager::class.java)
        if (manager.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            controller.repo.emit(id, "focus", "active_call", outcome = "DENIED"); return
        }
        controller.repo.emit(id, "focus", "screen_interactive", serviceContext.getSystemService(android.os.PowerManager::class.java).isInteractive.toString())
        focus?.let(manager::abandonAudioFocusRequest)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> controller.repo.emit(id, "focus", "change", change.toString()) }.build()
        focus = request
        val result = try { manager.requestAudioFocus(request) }
        catch (error: RuntimeException) { controller.repo.emit(id, "focus", "platform_rejected", error.javaClass.simpleName, "UNSUPPORTED"); return }
        controller.repo.emit(id, "focus", "request_result", if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED")
        scope.launch { delay(1_500); manager.abandonAudioFocusRequest(request); if (focus === request) focus = null }
    }
    override fun onHide() { focusJob?.cancel(); focus?.let(serviceContext.getSystemService(AudioManager::class.java)::abandonAudioFocusRequest); focus = null; if (controller.session === this) { controller.session = null; controller.interrupt("session_hidden") }; super.onHide() }
    override fun onDestroy() {
        focus?.let(serviceContext.getSystemService(AudioManager::class.java)::abandonAudioFocusRequest)
        if (controller.session === this) { controller.session = null; controller.interrupt("session_destroyed") }
        scope.cancel(); super.onDestroy()
    }
}
