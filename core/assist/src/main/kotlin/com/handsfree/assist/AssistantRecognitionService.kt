package com.handsfree.assist

import android.content.Intent
import android.os.RemoteException
import android.util.Log
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        rejectUnsupported(listener::error)
    }
    internal fun rejectUnsupported(reportError: (Int) -> Unit) {
        try { reportError(SpeechRecognizer.ERROR_CLIENT) }
        catch (_: RemoteException) { Log.w("AssistantRecognitionService", "recognition_client_disconnected") }
    }
    override fun onCancel(listener: Callback) = Unit
    override fun onStopListening(listener: Callback) = Unit
}
