package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.*
import kotlinx.coroutines.*

open class RecognitionAdapter : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onError(error: Int) = Unit
    override fun onResults(results: Bundle?) = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
object GoogleEngine {
    fun find(context: Context): ComponentName? = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .map { it.serviceInfo }.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
        ?.let { ComponentName(it.packageName, it.name) }
}
class SpeechEngine(private val context: Context, private val repo: ProbeRepository, private val safe: () -> Boolean) {
    private var recognizer: SpeechRecognizer? = null
    private var completion: CompletableDeferred<String?>? = null
    private var emptyWindows = 0
    val isListening: Boolean get() = recognizer != null
    var onStopWord: () -> Unit = {}
    var onEmptyWindows: suspend (String) -> Unit = {}
    suspend fun listen(id: String, defaultClient: Boolean = false, extra: Intent? = null, timeoutMs: Long = 8_000): String? = withContext(Dispatchers.Main.immediate) {
        if (context.checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { repo.emit(id, "recognition", "permission_denied", outcome = "PERMISSION_DENIED"); return@withContext null }
        if (!safe()) { repo.emit(id, "recognition", "active_or_unknown_call", outcome = "DENIED"); return@withContext null }
        close(id)
        val done = CompletableDeferred<String?>(); completion = done
        val onDevice = !defaultClient && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val engine = if (onDevice || defaultClient) null else GoogleEngine.find(context)
        if (!onDevice && !defaultClient && engine == null) { repo.emit(id, "recognition", "google_engine_missing", outcome = "UNSUPPORTED"); return@withContext null }
        repo.emit(id, "recognition", "engine", "createOnDevice=$onDevice;defaultClient=$defaultClient;component=${engine?.flattenToShortString() ?: if (onDevice) "platform_on_device" else "system_default"}")
        engine?.let { repo.emit(id, "recognition", "engine_version", repo.packageVersion(it.packageName)) }
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val speech = when {
                onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                defaultClient -> SpeechRecognizer.createSpeechRecognizer(context)
                else -> SpeechRecognizer.createSpeechRecognizer(context, engine!!)
            }
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionAdapter() {
                override fun onReadyForSpeech(params: Bundle?) { repo.emit(id, "recognition", "ready_ms", (android.os.SystemClock.elapsedRealtime() - started).toString()) }
                override fun onEndOfSpeech() { repo.emit(id, "recognition", "end_of_speech_ms", (android.os.SystemClock.elapsedRealtime() - started).toString()) }
                override fun onError(error: Int) { repo.emit(id, "recognition", "error", error.toString(), "ERROR"); done.complete(null) }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.any { it.trim().equals("stop", true) } == true) {
                        onStopWord(); done.complete("stop")
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    repo.emit(id, "recognition", "result_ms", "elapsed=${android.os.SystemClock.elapsedRealtime() - started};textPresent=${!text.isNullOrBlank()}")
                    if (text?.trim().equals("stop", true)) onStopWord()
                    done.complete(text)
                }
            })
            val intent = extra ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            speech.startListening(intent)
            val text = withTimeoutOrNull(timeoutMs) { done.await() }
            if (text.isNullOrBlank()) emptyWindows++ else emptyWindows = 0
            if (emptyWindows >= 2 && safe()) { close(id); onEmptyWindows(id) }
            text
        } catch (_: SecurityException) { repo.emit(id, "recognition", "permission_denied", outcome = "PERMISSION_DENIED"); null }
        catch (_: IllegalArgumentException) { repo.emit(id, "recognition", "unsupported_configuration", outcome = "UNSUPPORTED"); null }
        finally { close(id) }
    }
    fun close(id: String) {
        val active = recognizer
        recognizer = null
        if (active != null) { active.cancel(); active.destroy(); repo.emit(id, "recognition", "client_closed") }
        completion?.complete(null); completion = null
    }
}
