package com.handsfree.probes

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID

class AudioEngine(private val context: Context, private val repo: ProbeRepository, private val bluetoothConsent: () -> Boolean, private val disconnected: () -> Unit) {
    val manager: AudioManager = context.getSystemService(AudioManager::class.java)
    private var requested: Int? = null
    private var routeReady: CompletableDeferred<Boolean>? = null
    var confirmed: Boolean = false
        private set
    private var tts: TextToSpeech? = null
    private var initialized: CompletableDeferred<Boolean>? = null
    private var utterance: CompletableDeferred<Boolean>? = null
    private var speakingPrivate = false
    private var currentExperiment = "T4"
    private val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (device != null && requested != null && device.id == requested) { confirmed = true; routeReady?.complete(true); repo.emit(currentExperiment, "audio", "device_callback", "type=${device.type};id=${device.id}") }
        else if (requested != null) {
            confirmed = false; stopSpeech(); routeReady?.complete(false); disconnected()
            repo.emit(currentExperiment, "audio", "route_lost", outcome = "STOPPED")
        }
    }
    init { manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener) }
    suspend fun open(id: String, scoOnly: Boolean = false): Long? = withContext(Dispatchers.Main.immediate) {
        currentExperiment = id
        val bluetoothAllowed = context.checkSelfPermission(BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED && bluetoothConsent()
        if (scoOnly && !bluetoothAllowed) { repo.emit(id, "audio", "bluetooth_permission_denied", outcome = "PERMISSION_DENIED"); return@withContext null }
        val available = manager.availableCommunicationDevices
        val device = available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && bluetoothAllowed }
            ?: if (scoOnly) null else available.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: if (scoOnly) null else available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (device == null) { close(); repo.emit(id, "audio", "route_unavailable", outcome = "UNSUPPORTED"); return@withContext null }
        if (manager.communicationDevice?.id != device.id) { close(); delay(100) }
        routeReady?.complete(false)
        val ready = CompletableDeferred<Boolean>(); routeReady = ready; requested = device.id
        val started = SystemClock.elapsedRealtime()
        try {
            if (manager.communicationDevice?.id == device.id) {
                confirmed = true; ready.complete(true)
                repo.emit(id, "audio", "already_active", "type=${device.type};id=${device.id}")
                repo.emit(id, "audio", "route_confirmed_ms", "0")
                return@withContext 0L
            }
            if (!manager.setCommunicationDevice(device)) { repo.emit(id, "audio", "route_request_rejected", outcome = "FAILED"); return@withContext null }
            if (withTimeoutOrNull(2_000) { ready.await() } != true) { repo.emit(id, "audio", "route_callback_timeout", outcome = "FAILED"); close(); return@withContext null }
            val elapsed = SystemClock.elapsedRealtime() - started
            repo.emit(id, "audio", "route_confirmed_ms", elapsed.toString())
            elapsed
        } catch (_: SecurityException) { repo.emit(id, "audio", "permission_denied", outcome = "PERMISSION_DENIED"); close(); null }
    }
    fun close() { confirmed = false; requested = null; routeReady?.complete(false); routeReady = null; manager.clearCommunicationDevice() }
    private suspend fun ensureTts(): Boolean {
        initialized?.let { return withTimeoutOrNull(3_000) { it.await() } == true }
        val ready = CompletableDeferred<Boolean>(); initialized = ready
        tts = TextToSpeech(context) { result -> ready.complete(result == TextToSpeech.SUCCESS) }
        return withTimeoutOrNull(3_000) { ready.await() } == true
    }
    suspend fun speak(id: String, text: String, personal: Boolean = false): Boolean = withContext(Dispatchers.Main.immediate) {
        if (personal && !confirmed) { repo.emit(id, "tts", "unconfirmed_private_route", outcome = "DENIED"); return@withContext false }
        if (!ensureTts()) { repo.emit(id, "tts", "engine_unavailable", outcome = "UNSUPPORTED"); return@withContext false }
        currentCoroutineContext().ensureActive()
        val engine = tts!!
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val local = engine.voices.orEmpty().firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language }
        var spoken = text
        if (local == null) { spoken = "A local voice is unavailable"; repo.emit(id, "tts", "local_voice_missing", outcome = "FAILED") }
        else engine.voice = local
        repo.emit(id, "tts", "voice", "engine=${engine.defaultEngine};version=${repo.packageVersion(engine.defaultEngine)};voice=${engine.voice?.name};networkRequired=${engine.voice?.isNetworkConnectionRequired}")
        if (personal && !confirmed) { repo.emit(id, "tts", "route_lost_before_speech", outcome = "DENIED"); return@withContext false }
        repo.spoken.value = spoken
        val done = CompletableDeferred<Boolean>(); utterance = done; speakingPrivate = personal
        val expectedId = "probe-${UUID.randomUUID()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == expectedId) done.complete(true) }
            override fun onError(utteranceId: String?) { if (utteranceId == expectedId) done.complete(false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == expectedId) done.complete(false) }
        })
        if (engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), expectedId) == TextToSpeech.ERROR) done.complete(false)
        try {
            val completed = withTimeoutOrNull(15_000) { done.await() }
            if (completed == null) engine.stop()
            completed == true && local != null
        }
        finally { speakingPrivate = false; utterance = null; if (repo.spoken.value == spoken) repo.spoken.value = "" }
    }
    fun stopSpeech() { repo.spoken.value = ""; tts?.stop(); utterance?.complete(false); speakingPrivate = false }
}
