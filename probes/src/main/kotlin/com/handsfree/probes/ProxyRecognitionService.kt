package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.speech.*
import java.util.concurrent.atomic.AtomicInteger

object ProxyStats { val requests = AtomicInteger(); val results = AtomicInteger(); val cancellations = AtomicInteger() }
class ProxyRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null
    private var activeCallback: Callback? = null
    private val interruptor: () -> Unit = {
        if (delegate != null) {
            val callback = activeCallback
            delegate?.cancel()
            close()
            ProxyStats.cancellations.incrementAndGet()
            try { callback?.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: RemoteException) { }
            controller.repo.emit("T1a", "proxy", "interrupted", outcome = "CANCELLED")
        }
    }
    override fun onCreate() { super.onCreate(); controller.registerRecognitionInterruptor(interruptor) }
    private val controller get() = (application as ProbesApp).controller
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val caller = listener.callingAttributionSource
        controller.repo.emit("T1a", "proxy", "caller", "uid=${caller.uid};package=${caller.packageName}")
        val started = SystemClock.elapsedRealtime()
        ProxyStats.requests.incrementAndGet()
        controller.repo.emit("T1a", "proxy", "request", "count=${ProxyStats.requests.get()};createOnDevice=false")
        if (checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || RECORD_AUDIO !in controller.config.consents || !controller.phones.safeToListen()) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return
        }
        val component = GoogleEngine.find(this)
        if (component == null) { listener.error(SpeechRecognizer.ERROR_CLIENT); controller.repo.emit("T1a", "proxy", "google_engine_missing", outcome = "UNSUPPORTED"); return }
        if (delegate != null) { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        try {
            val attributed = createContext(ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build())
            val recognizer = SpeechRecognizer.createSpeechRecognizer(attributed, component)
            delegate = recognizer
            activeCallback = listener
            controller.repo.emit("T1a", "proxy", "google_version", controller.repo.packageVersion(component.packageName))
            recognizer.setRecognitionListener(object : RecognitionAdapter() {
                private fun forward(block: () -> Unit) { if (delegate !== recognizer || activeCallback !== listener) return; try { block() } catch (_: RemoteException) { close(recognizer) } }
                override fun onReadyForSpeech(params: Bundle?) = forward { listener.readyForSpeech(params ?: Bundle()) }
                override fun onBeginningOfSpeech() = forward { listener.beginningOfSpeech() }
                override fun onRmsChanged(rmsdB: Float) = forward { listener.rmsChanged(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) = forward { listener.bufferReceived(buffer ?: byteArrayOf()) }
                override fun onEndOfSpeech() = forward { listener.endOfSpeech() }
                override fun onError(error: Int) { if (delegate !== recognizer) return; controller.repo.emit("T1a", "proxy", "error", error.toString(), "ERROR"); forward { listener.error(error) }; close(recognizer) }
                override fun onResults(results: Bundle?) {
                    if (delegate !== recognizer) return
                    ProxyStats.results.incrementAndGet()
                    controller.repo.emit("T1a", "proxy", "result", "count=${ProxyStats.results.get()};elapsed=${SystemClock.elapsedRealtime() - started}")
                    forward { listener.results(results ?: Bundle()) }; close(recognizer)
                }
                override fun onPartialResults(partialResults: Bundle?) = forward { listener.partialResults(partialResults ?: Bundle()) }
                override fun onEvent(eventType: Int, params: Bundle?) { controller.repo.emit("T1a", "proxy", "engine_event", eventType.toString()) }
                override fun onSegmentResults(segmentResults: Bundle) { if (Build.VERSION.SDK_INT >= 33) forward { listener.segmentResults(segmentResults) } }
                override fun onEndOfSegmentedSession() { if (Build.VERSION.SDK_INT >= 33) forward { listener.endOfSegmentedSession() }; close(recognizer) }
            })
            recognizer.startListening(Intent(recognizerIntent))
        } catch (_: SecurityException) { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); close() }
        catch (_: IllegalArgumentException) { listener.error(SpeechRecognizer.ERROR_CLIENT); close() }
    }
    override fun onStopListening(listener: Callback) { delegate?.stopListening() }
    override fun onCancel(listener: Callback) { ProxyStats.cancellations.incrementAndGet(); controller.repo.emit("T1a", "proxy", "cancel", ProxyStats.cancellations.get().toString()); delegate?.cancel(); close() }
    private fun close(expected: SpeechRecognizer? = delegate) { if (delegate !== expected) return; val previous = delegate; delegate = null; activeCallback = null; previous?.destroy() }
    override fun onDestroy() { controller.unregisterRecognitionInterruptor(interruptor); close(); super.onDestroy() }
}
