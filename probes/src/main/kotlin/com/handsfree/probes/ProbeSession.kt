package com.handsfree.probes

import android.content.Context
import android.media.*
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
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
    override fun onCreateContentView(): View {
        val body = LinearLayout(serviceContext).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        body.addView(TextView(serviceContext).apply { text = "Probes assistant session — screen-off / lock tests" })
        actions = LinearLayout(serviceContext).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        refreshActions(controller.currentId)
        body.addView(Button(serviceContext).apply { text = "Stop"; setOnClickListener { controller.stop("session_stop"); hide() } })
        val text = TextView(serviceContext); body.addView(text)
        scope.launch { combine(controller.repo.lines, controller.repo.spoken) { lines, spoken ->
            val locked = serviceContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            lines.takeLast(12).joinToString("\n") { if (locked) it.substringBefore(":") else it } + "\n" + spoken
        }.collect { text.text = it } }
        return ScrollView(serviceContext).apply { addView(body); content = this }
    }
    private fun refreshActions(id: String) {
        val buttons = actions ?: return
        buttons.removeAllViews()
        if (id in setOf("T2", "T10")) {
            listOf("call", "play", "navigation", "timer", "app").forEach { kind ->
                buttons.addView(Button(serviceContext).apply { text = kind; setOnClickListener { controller.launchFromSession(kind, this@ProbeSession) } })
            }
        }
        if (id == "T7") buttons.addView(Button(serviceContext).apply { text = "Request focus (T7)"; setOnClickListener { requestFocus() } })
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val id = args?.getString("experiment") ?: controller.currentId
        refreshActions(id)
        controller.sessionShown(id, this)
        if (id == "T7") {
            focusJob?.cancel()
            focusJob = scope.launch {
                val ready = withTimeoutOrNull(2_000) { while (content?.hasWindowFocus() != true) delay(20); true }
                if (ready == true && controller.session === this@ProbeSession) requestFocus()
                else controller.repo.emit("T7", "focus", "session_window_not_focused", outcome = "UNSUPPORTED")
            }
        }
    }
    fun requestFocus() {
        val manager = serviceContext.getSystemService(AudioManager::class.java)
        if (manager.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            controller.repo.emit("T7", "focus", "active_call", outcome = "DENIED"); return
        }
        controller.repo.emit("T7", "focus", "screen_interactive", serviceContext.getSystemService(android.os.PowerManager::class.java).isInteractive.toString())
        focus?.let(manager::abandonAudioFocusRequest)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> controller.repo.emit("T7", "focus", "change", change.toString()) }.build()
        focus = request
        val result = try { manager.requestAudioFocus(request) }
        catch (error: RuntimeException) { controller.repo.emit("T7", "focus", "platform_rejected", error.javaClass.simpleName, "UNSUPPORTED"); return }
        controller.repo.emit("T7", "focus", "request_result", if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED")
        scope.launch { delay(1_500); manager.abandonAudioFocusRequest(request); if (focus === request) focus = null }
    }
    override fun onHide() { focusJob?.cancel(); focus?.let(serviceContext.getSystemService(AudioManager::class.java)::abandonAudioFocusRequest); focus = null; if (controller.session === this) { controller.session = null; controller.interrupt("session_hidden") }; super.onHide() }
    override fun onDestroy() {
        focus?.let(serviceContext.getSystemService(AudioManager::class.java)::abandonAudioFocusRequest)
        if (controller.session === this) { controller.session = null; controller.interrupt("session_destroyed") }
        scope.cancel(); super.onDestroy()
    }
}
