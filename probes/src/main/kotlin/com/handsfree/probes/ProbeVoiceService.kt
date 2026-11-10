package com.handsfree.probes

import android.content.Intent
import android.os.Bundle
import android.service.voice.*

class ProbeVoiceService : VoiceInteractionService() {
    private val controller get() = (application as ProbesApp).controller
    override fun onReady() {
        super.onReady(); current = this
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_SCREENSHOT or VoiceInteractionSession.SHOW_WITH_ASSIST)
        controller.repo.emit("T12", "assistant", "ready_context_disabled")
    }
    override fun onLaunchVoiceAssistFromKeyguard() {
        controller.repo.emit(controller.currentId, "keyguard", "launch_callback")
        startActivity(Intent(this, LockProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("experiment", controller.currentId))
        controller.showSession(controller.currentId)
    }
    override fun onPrepareToShowSession(args: Bundle, flags: Int) {
        super.onPrepareToShowSession(args, flags)
        controller.repo.emit(controller.currentId, "assistant", "session_invoked", "flags=$flags")
    }
    override fun onShutdown() { controller.roleLost(); current = null; super.onShutdown() }
    override fun onDestroy() { if (current === this) current = null; controller.roleLost(); super.onDestroy() }
    companion object { var current: ProbeVoiceService? = null; private set }
}
class ProbeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ProbeSession(this)
}
