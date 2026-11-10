package com.handsfree.probes

import android.Manifest.permission.*
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.handsfree.guard.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.ceil

@Singleton
class ProbeController internal constructor(
    val context: android.content.Context, val repo: ProbeRepository, val exports: ProbeExports,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    internal val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(@ApplicationContext context: android.content.Context, repo: ProbeRepository) :
        this(context, repo, ProbeExports(context, repo))
    var currentId = "T1a"
        private set
    var config = ProbeConfig()
        private set
    private var job: Job? = null
    private var ringingJob: Job? = null
    private var ringingArmed = false
    val screeningEnabled: Boolean get() = ringingArmed
    private var actionJob: Job? = null
    private var currentAudioSource: AudioSource? = null
    private val recognitionInterruptors = mutableSetOf<() -> Unit>()
    internal fun registerRecognitionInterruptor(stop: () -> Unit) { recognitionInterruptors += stop }
    internal fun unregisterRecognitionInterruptor(stop: () -> Unit) { recognitionInterruptors -= stop }
    val consents = ProbeConsentStore()
    fun setConsent(capability: String, granted: Boolean, experiment: String = currentId) {
        val changed = consents.set(capability, granted)
        repo.emit(experiment, "consent", "consent_answer", "capability=$capability;granted=$granted", if (granted) "GRANTED" else "DENIED")
        if (changed && !granted) stop("consent_revoked")
        exports.automatically("consent_answer", experiment)
    }
    fun permissionAnswer(experiment: String, capability: String, granted: Boolean) {
        repo.emit(experiment, "permission", "permission_answer", "capability=$capability;granted=$granted", if (granted) "GRANTED" else "DENIED")
        exports.automatically("permission_answer", experiment)
    }
    val audio = AudioEngine(context, repo, { BLUETOOTH_CONNECT in consents }) { interrupt("route_lost") }
    val phones = PhoneMonitor(context, repo) { interrupt("call_state") }
    val integrations = IntegrationProbes(context, repo, consents) { config }
    val speech = SpeechEngine(context, repo) { phones.safeToListen() }
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var permissionSnapshot: Map<String, Boolean>? = null
    var session: ProbeSession? = null
    private var sessionReady: CompletableDeferred<ProbeSession>? = null
    private var expectedSessionId: String? = null
    init {
        audio.manager.addOnModeChangedListener(context.mainExecutor) { mode ->
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                interrupt("audio_mode")
                if (currentId == "T3") context.stopService(Intent(context, MicrophoneService::class.java))
            }
        }
        speech.onStopWord = { audio.stopSpeech() }
        speech.onEmptyWindows = { id ->
            if (phones.safeToListen() && (id != "T13" || audio.confirmed) && (id != "T1b" || phones.isRinging())) audio.speak(id, "I can't hear you; check the microphone and the privacy toggle")
        }
        val locked = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                this@ProbeController.context.stopService(Intent(this@ProbeController.context, MicrophoneService::class.java))
                interrupt("screen_off"); ringingJob?.cancel(); job?.cancel(); actionJob?.cancel()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(locked, IntentFilter(Intent.ACTION_SCREEN_OFF), android.content.Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(locked, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    fun roleHeld(): Boolean = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    fun start(id: String, options: ProbeConfig) {
        stop("new_experiment")
        currentId = id; config = options
        speech.experimentChanged(id)
        ProbeNotificationListener.current?.refreshVisibleNotifications()
        repo.emit(id, "start", "requested", repo.deviceInfo())
        val experiment = Experiments.find(id) ?: run { exports.automatically("unknown_experiment", id); return }
        if (id == "T13" && Build.VERSION.SDK_INT < 33) { repo.emit(id, "start", "api_33_required", outcome = "UNSUPPORTED"); exports.automatically("run_end", id); return }
        val required = experiment.required.filter { it != POST_NOTIFICATIONS || Build.VERSION.SDK_INT >= 33 }
        if (required.any { it !in consents }) { repo.emit(id, "start", "internal_consent_missing", outcome = "CONSENT_DENIED"); exports.automatically("run_end", id); return }
        if (required.any { !permissionGranted(it) }) { repo.emit(id, "start", "permission_denied", outcome = "PERMISSION_DENIED"); exports.automatically("run_end", id); return }
        job = scope.launch {
            try {
                when (id) {
                    "T1a" -> { preparePhone(id); val before = ProxyStats.requests.get(); speech.listen(id, defaultClient = true); repo.emit(id, "proxy", "requests_received", (ProxyStats.requests.get() - before).toString()) }
                    "T1b" -> { ringingArmed = true; preparePhone(id); repo.emit(id, "screening", "armed_waiting_for_call") }
                    "T1c" -> startModeAndLegacy()
                    "T2", "T7", "T10" -> {
                        if (id != "T7") preparePhone(id)
                        if (id == "T10") repo.emit(id, "lock", "security_state", "secure=${context.getSystemService(KeyguardManager::class.java).isDeviceSecure};locked=${context.getSystemService(KeyguardManager::class.java).isKeyguardLocked}")
                        showSession(id)
                    }
                    "T5" -> integrations.whatsapp()
                    "T6" -> integrations.mediaSearch()
                    "T8" -> { preparePhone(id); if (phones.states.value.values.any { it == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK }) startMicrophone(id) else repo.emit(id, "pcm", "cellular_call_required", outcome = "UNSUPPORTED") }
                    "T11" -> { if (READ_PHONE_STATE in consents) preparePhone(id); integrations.startButtons() }
                    "T3" -> { repo.emit(id, "fgs", "switch_apps_within_ms", "5000"); delay(5_000); startMicrophone(id) }
                    "T4" -> measureSco()
                    "T9" -> repo.emit(id, "role", "request_is_activity_owned")
                    "T12" -> permissionSnapshot()
                    "T13" -> { preparePhone(id); audioSourceTrials() }
                }
            } catch (cancelled: CancellationException) { repo.emit(id, "run", "cancelled", outcome = "CANCELLED"); throw cancelled }
            catch (error: Exception) { repo.emit(id, "run", "platform_failure", error.javaClass.simpleName, "UNSUPPORTED") }
            finally { exports.automatically("run_end", id, listOf(currentCoroutineContext().job)) }
        }
    }
    private fun permissionGranted(permission: String): Boolean = if (permission == ProbeNotificationListener.ACCESS) {
        context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, ProbeNotificationListener::class.java))
    } else context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun exportResults(share: ((Intent) -> Unit)? = null) {
        repo.scope.launch {
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked && !config.personalResultsOnLock) {
                repo.emit("export", "guard", "locked", outcome = "DENIED"); return@launch
            }
            if (config.appPackage.isNotBlank()) repo.packageVersion(config.appPackage)
            val files = exports.save(if (share == null) "manual" else "share", "export", forSharing = share != null) ?: return@launch
            if (share != null) withContext(Dispatchers.Main.immediate) { share(exports.shareIntent(files)) }
        }
    }
    private suspend fun preparePhone(id: String) {
        phones.start(id)
        withTimeoutOrNull(2_000) { phones.states.first { it.values.all { state -> state != null } } }
    }
    private fun startModeAndLegacy() {
        val listener = AudioManager.OnModeChangedListener { mode ->
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) interrupt("audio_mode")
            repo.emit("T1c", "audio_mode", "changed", mode.toString())
        }
        modeListener = listener
        audio.manager.addOnModeChangedListener(context.mainExecutor, listener)
        repo.emit("T1c", "audio_mode", "initial_no_dangerous_permission", audio.manager.mode.toString())
        if (READ_CALL_LOG in consents && READ_PHONE_STATE in consents) phones.start("T1c", legacyNumber = true)
        else repo.emit("T1c", "legacy", "internal_consent_missing", outcome = "CONSENT_DENIED")
    }
    fun sessionShown(id: String, shown: ProbeSession) {
        actionJob?.cancel()
        session = shown
        if (expectedSessionId == id) sessionReady?.complete(shown)
        repo.emit(id, "session", "shown", "screenInteractive=${context.getSystemService(PowerManager::class.java).isInteractive}")
        if (id in setOf("T2", "T10", "T5")) {
            actionJob?.cancel()
            actionJob = scope.launch {
                try {
                    if (RECORD_AUDIO !in consents || READ_PHONE_STATE !in consents || context.checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                        repo.emit(id, "session_voice", "permission_or_consent_denied", outcome = "PERMISSION_DENIED"); return@launch
                    }
                    preparePhone(id)
                    if (!phones.safeToListen()) return@launch
                    ensureSession(shown)
                    audio.speak(id, when (id) { "T5" -> "Say answer or decline"; "T10" -> "Say call, navigation, or timer"; else -> "Say call, play, navigation, timer, or app" })
                    ensureSession(shown)
                    val command = speech.listen(id)?.trim()?.lowercase()
                    val kind = when (command) {
                        "answer" -> "Answer"
                        "decline" -> "Decline"
                        "call" -> "call"
                        "play" -> "play"
                        "navigation", "navigate" -> "navigation"
                        "timer", "set a timer" -> "timer"
                        "app", "open app" -> "app"
                        else -> null
                    }
                    ensureSession(shown)
                    val allowed = when (id) { "T5" -> setOf("Answer", "Decline"); "T10" -> setOf("call", "navigation", "timer"); else -> setOf("call", "play", "navigation", "timer", "app") }
                    if (kind != null && kind in allowed) {
                        if (id == "T5") integrations.whatsapp(kind) else launchFromSession(kind, shown)
                    } else repo.emit(id, "session_voice", "command_not_matched", outcome = "CANCELLED")
                } catch (cancelled: CancellationException) { repo.emit(id, "session_voice", "cancelled", outcome = "CANCELLED"); throw cancelled }
                catch (error: Exception) { repo.emit(id, "session_voice", "platform_failure", error.javaClass.simpleName, "UNSUPPORTED") }
                finally { exports.automatically("session_voice_end", id, listOf(currentCoroutineContext().job)) }
            }
        }
    }
    fun showSession(id: String) {
        expectedSessionId = id
        sessionReady = CompletableDeferred()
        val service = ProbeVoiceService.current
        if (service == null || !roleHeld()) { repo.emit(id, "session", "assistant_not_ready", outcome = "UNSUPPORTED"); return }
        try { service.showSession(Bundle().apply { putString("experiment", id) }, 0); repo.emit(id, "session", "show_requested") }
        catch (error: RuntimeException) { repo.emit(id, "session", "show_failed", error.javaClass.simpleName, "FAILED") }
    }
    fun screened(number: String?) {
        if (!ringingArmed) return
        ringingJob?.cancel()
        showSession("T1b")
        ringingJob = scope.launch {
            try {
                val shown = withTimeoutOrNull(2_000) { sessionReady?.await() }
                if (shown == null) {
                    repo.emit("T1b", "session", "show_timeout", outcome = "FAILED"); return@launch
                }
                withTimeoutOrNull(2_000) { phones.states.first { phones.exactlyOneRinging() && phones.safeToListen() } }
                if (!phones.exactlyOneRinging() || !phones.safeToListen()) { repo.emit("T1b", "ringing", "unsafe_or_unknown_state", outcome = "DENIED"); return@launch }
                if (audio.open("T1b") == null) return@launch
                val name = number?.let { savedName(it) }
                if (!phones.exactlyOneRinging() || !phones.safeToListen() || !audio.confirmed) return@launch
                ensureSession(shown)
                if (!audio.speak("T1b", name?.let { "Incoming call from $it" } ?: "Incoming call", personal = name != null)) return@launch
                if (!phones.exactlyOneRinging() || !phones.safeToListen()) return@launch
                ensureSession(shown)
                val answer = speech.listen("T1b", timeoutMs = 8_000)
                ensureSession(shown)
                if (answer?.trim().equals("Answer", true) && phones.exactlyOneRinging() && phones.safeToListen()) {
                    if (context.checkSelfPermission(ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED && ANSWER_PHONE_CALLS in consents && roleHeld()) {
                        context.getSystemService(TelecomManager::class.java).acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY)
                        repo.emit("T1b", "answer", "audio_only_requested")
                    } else repo.emit("T1b", "answer", "permission_or_consent_denied", outcome = "PERMISSION_DENIED")
                }
            } catch (cancelled: CancellationException) { repo.emit("T1b", "ringing", "cancelled", outcome = "CANCELLED"); throw cancelled }
            catch (error: Exception) { repo.emit("T1b", "ringing", "platform_failure", error.javaClass.simpleName, "FAILED") }
            finally { speech.close("T1b"); audio.stopSpeech(); audio.close(); exports.automatically("ringing_end", "T1b", listOf(currentCoroutineContext().job)) }
        }
    }
    private suspend fun savedName(number: String): String? = withContext(workerDispatcher) {
        if (READ_CONTACTS !in consents || context.checkSelfPermission(READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
    private suspend fun measureSco() {
        val measurements = mutableListOf<Long>()
        repeat(20) { trial ->
            repo.emit("T4", "sco", "trial", (trial + 1).toString())
            audio.open("T4", scoOnly = true)?.let(measurements::add)
            audio.close(); delay(100)
        }
        if (measurements.isNotEmpty()) {
            val sorted = measurements.sorted()
            val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0 else sorted[sorted.size / 2].toDouble()
            repo.emit("T4", "sco", "summary_ms", "attempts=20;confirmed=${sorted.size};median=$median;p90=${sorted[ceil(sorted.size * 0.9).toInt() - 1]}")
        }
        if (audio.open("T4", scoOnly = true) != null) {
            try { sentences.forEach { if (!audio.confirmed) return; audio.speak("T4", it) } }
            finally { audio.stopSpeech(); audio.close() }
        }
    }
    private suspend fun audioSourceTrials() {
        if (audio.open("T13", scoOnly = true) == null) return
        var stops = 0
        try {
            repeat(20) { trial ->
                if (!phones.safeToListen() || !audio.confirmed) { repo.emit("T13", "trial", "unsafe_route_or_call", outcome = "DENIED"); return }
                val source = AudioSource.create(context, repo, "T13") ?: return
                currentAudioSource = source
                try {
                    val result = coroutineScope {
                        val utterance = launch { audio.speak("T13", sentences[trial % sentences.size]) }
                        try { speech.listen("T13", extra = source.intent(), timeoutMs = 8_000) }
                        finally { audio.stopSpeech(); withContext(NonCancellable) { utterance.cancelAndJoin() } }
                    }
                    if (result?.trim().equals("stop", true)) stops++
                    repo.emit("T13", "trial", "stop_count", "trial=${trial + 1};stops=$stops")
                } finally { source.close(); if (currentAudioSource === source) currentAudioSource = null }
            }
            repo.emit("T13", "summary", "stops_of_twenty", stops.toString())
        } finally { audio.stopSpeech(); audio.close() }
    }
    fun startMicrophone(id: String) {
        if (id != "T8" && audio.manager.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            repo.emit(id, "fgs", "active_call", outcome = "DENIED"); return
        }
        try { context.startForegroundService(Intent(context, MicrophoneService::class.java).putExtra("experiment", id)) }
        catch (error: RuntimeException) { repo.emit(id, "fgs", "start_rejected", error.javaClass.simpleName, "DENIED") }
    }
    private fun permissionSnapshot() {
        val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        val current = declared.associateWith { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        current.forEach { (permission, granted) -> repo.emit("T12", "snapshot", permission, "granted=$granted;previous=${permissionSnapshot?.get(permission)};roleHeld=${roleHeld()}") }
        permissionSnapshot = current
    }
    private suspend fun ensureSession(shown: ProbeSession) {
        currentCoroutineContext().ensureActive()
        if (session !== shown) throw CancellationException("Session is hidden or replaced")
    }
    fun launchFromSession(kind: String, activeSession: ProbeSession) {
        if (session !== activeSession) { repo.emit(currentId, "session", "hidden_action_rejected", outcome = "CANCELLED"); return }
        actionJob?.cancel()
        actionJob = scope.launch {
            val id = currentId
            try {
                val intent = when (kind) {
                    "call" -> { confirmedCall(activeSession); return@launch }
                    "play" -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(android.app.SearchManager.QUERY, config.query)
                    "navigation" -> Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(config.destination)}"))
                    "timer" -> Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 60).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    "app" -> context.packageManager.getLaunchIntentForPackage(config.appPackage)
                    else -> null
                }
                if (intent == null) { repo.emit(id, "intent", "unavailable", kind, "UNSUPPORTED"); return@launch }
                ensureSession(activeSession)
                activeSession.startAssistantActivity(intent)
                repo.emit(id, "intent", "launch_requested", "$kind;screenInteractive=${context.getSystemService(PowerManager::class.java).isInteractive}")
            } catch (cancelled: CancellationException) { repo.emit(id, "intent", "cancelled", outcome = "CANCELLED"); throw cancelled }
            catch (_: ActivityNotFoundException) { repo.emit(id, "intent", "no_handler", kind, "UNSUPPORTED") }
            catch (error: RuntimeException) { repo.emit(id, "intent", "launch_rejected", error.javaClass.simpleName, "FAILED") }
            finally { exports.automatically("action_end", id, listOf(currentCoroutineContext().job)) }
        }
    }
    private suspend fun confirmedCall(activeSession: ProbeSession) {
        ensureSession(activeSession)
        val captured = config
        val id = currentId
        if (!captured.number.matches(Regex("[+0-9() -]{7,30}")) || captured.number.count(Char::isDigit) !in 7..15) { repo.emit(id, "call", "invalid_test_number", outcome = "DENIED"); return }
        if (context.checkSelfPermission(CALL_PHONE) != PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) { repo.emit(id, "call", "permission_denied", outcome = "PERMISSION_DENIED"); return }
        val telecom = context.getSystemService(TelecomManager::class.java)
        val handle = telecom.callCapablePhoneAccounts.getOrNull(captured.phoneAccountIndex)
        if (handle == null) { repo.emit(id, "call", "selected_account_missing", outcome = "UNSUPPORTED"); return }
        suspend fun snapshot(): ActionSnapshot {
            val name = savedName(captured.number)
            return ActionSnapshot("call", ActionKind.CALL_OUT, captured.number, name, name != null, origins = mapOf("tool" to Origin.USER_UTTERANCE, "recipientId" to Origin.USER_UTTERANCE, "recipientLabel" to Origin.PLANNER_FROM_USER, "recipientSaved" to Origin.PLANNER_FROM_USER))
        }
        fun guardContext() = com.handsfree.guard.Context(roleHeld(), context.getSystemService(KeyguardManager::class.java).isKeyguardLocked, captured.personalResultsOnLock,
            if (context.checkSelfPermission(CALL_PHONE) == PackageManager.PERMISSION_GRANTED) setOf(Capability.CALL_PHONE) else emptySet(),
            if (CALL_PHONE in consents) setOf(Capability.CALL_PHONE) else emptySet(), !phones.safeToListen())
        val guard = Guard(); val spec = ToolSpec("call", emptyMap(), Capability.CALL_PHONE, Sensitivity.CONFIRM, ActionKind.CALL_OUT)
        val request = guard.evaluate(spec, snapshot(), guardContext())
        if (request !is Decision.NeedConfirm) { repo.emit(id, "call", "guard_denied", outcome = "DENIED"); return }
        ensureSession(activeSession)
        if (audio.open(id) == null) return
        try {
            ensureSession(activeSession)
            if (!audio.speak(id, request.prompt, personal = true)) return
            ensureSession(activeSession)
            val response = speech.listen(id)
            ensureSession(activeSession)
            if (response?.trim()?.lowercase() !in setOf("confirm", "yes")) { repo.emit(id, "call", "not_confirmed", outcome = "CANCELLED"); return }
            if (!roleHeld() || !phones.safeToListen() || config != captured || !telecom.callCapablePhoneAccounts.contains(handle) || guard.evaluate(spec, snapshot(), guardContext(), request.snapshot) !is Decision.Allow) {
                repo.emit(id, "call", "confirmation_invalidated", outcome = "DENIED"); return
            }
            if (context.checkSelfPermission(CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
            ensureSession(activeSession)
            activeSession.startAssistantActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", captured.number, null)).putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle))
            repo.emit(id, "call", "launch_requested_with_selected_account", "screenInteractive=${context.getSystemService(PowerManager::class.java).isInteractive}")
        } finally { speech.close(id); audio.stopSpeech(); audio.close() }
    }
    fun interrupt(reason: String) {
        val finishing = listOfNotNull(job, actionJob, ringingJob)
        finishing.forEach { it.cancel() }
        sessionReady?.cancel()
        val start = SystemClock.elapsedRealtime()
        val wasListening = speech.isListening
        recognitionInterruptors.toList().forEach { it() }
        speech.close(currentId); currentAudioSource?.close(); currentAudioSource = null; audio.stopSpeech(); audio.close()
        repo.emit(currentId, "interruption", "client_close_requested_ms", "reason=$reason;wasListening=$wasListening;clientClosed=${!speech.isListening};elapsed=${SystemClock.elapsedRealtime() - start};physicalMicClosure=manual_validation_required")
        exports.automatically("interrupt:$reason", currentId, finishing)
    }
    fun roleLost() { actionJob?.cancel(); phones.stopLegacy(); ringingArmed = false; ringingJob?.cancel(); interrupt("role_lost") }
    fun stop(reason: String) {
        ProbeNotificationListener.current?.clearNotifications()
        job?.cancel(); ringingJob?.cancel(); actionJob?.cancel(); ringingArmed = false
        integrations.stop()
        phones.stop(); modeListener?.let(audio.manager::removeOnModeChangedListener); modeListener = null
        context.stopService(Intent(context, MicrophoneService::class.java))
        interrupt(reason)
    }
    companion object {
        val sentences = listOf("The next turn is on your left.", "The timer is ready.", "Your note has been saved.", "The volume is now lower.", "There is an incoming call.", "The music is paused.", "You can speak now.", "The next exit is ahead.", "The reading has finished.", "This is the final test sentence.")
    }
}
