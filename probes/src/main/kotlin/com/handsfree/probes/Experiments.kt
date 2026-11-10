package com.handsfree.probes

import android.Manifest.permission.*

data class Experiment(val id: String, val title: String, val steps: String, val required: List<String> = emptyList(), val optional: List<String> = emptyList())
object Experiments {
    private val listening = listOf(RECORD_AUDIO, READ_PHONE_STATE)
    val all = listOf(
        Experiment("T1a", "Default recognition proxy", "Choose probes as assistant in system settings. Grant microphone and phone-state access, then Start and speak. Repeat and cancel. Compare Gboard manually. This client deliberately uses the default recognition engine.", listening),
        Experiment("T1b", "Screening to ringing session", "Request CALL_SCREENING and choose probes as assistant. Start, then call each active SIM from another phone. Test a saved contact with and without contact consent/permission. Connect HFP. Say Answer while ringing. End ringing and inspect client-close timing; check the microphone indicator manually.", listening + ANSWER_PHONE_CALLS, listOf(READ_CONTACTS, BLUETOOTH_CONNECT)),
        Experiment("T1c", "Audio mode and legacy caller number", "Start without phone permissions first; make, answer and end a test call. Audio-mode monitoring needs no dangerous permission. Then grant phone-state/call-log consent and permissions, keep the assistant role, and repeat for the legacy number-present measurement.", optional = listOf(READ_PHONE_STATE, READ_CALL_LOG)),
        Experiment("T2", "Launch intents from a session", "Enter a test number, query, destination and app package. Select the outgoing phone account. Start to show the session, turn the screen off and invoke the assistant. Say call, play, navigation, timer, or app in the session, one test at a time. Buttons also work with the screen on. Calling requires a second spoken confirmation. Record any blocked-launch dialog manually.", listening, listOf(CALL_PHONE, READ_CONTACTS, BLUETOOTH_CONNECT)),
        Experiment("T3", "Background microphone FGS", "Choose probes as assistant and Start. Switch to another app within five seconds. AudioRecord measures samples, not speech recognition. Repeat after withdrawing the assistant role with the same permissions.", listOf(RECORD_AUDIO, POST_NOTIFICATIONS)),
        Experiment("T4", "SCO latency and intelligibility", "Pair HFP and grant Bluetooth access. Start for 20 route-open measurements and ten fixed sentences. Enter how many sentences you understood and save the observation. Unplug the headset during speech to verify stopping.", listOf(BLUETOOTH_CONNECT)),
        Experiment("T7", "Transient focus from session", "Play music, turn the screen off, invoke the assistant session; focus is requested on show. Request focus also repeats it with the screen on. Do not start an FGS. Record manually whether music ducked; the numeric focus result is recorded automatically."),
        Experiment("T9", "Assistant role request dialog", "Start to call isRoleAvailable and createRequestRoleIntent for ROLE_ASSISTANT. Record whether a dialog appeared and its result. The normal assistant selection button opens system settings instead."),
        Experiment("T12", "Permission snapshots around role", "Start before choosing the assistant role. Choose probes in system settings without extra permission dialogs, then Start again. Withdraw the role and repeat. Compare every declared permission with the previous snapshot."),
        Experiment("T13", "Audio-source stop with AEC", "API 33+ only. Pair the car, grant microphone/phone-state/Bluetooth consent and permissions. Start 20 trials. Say stop while each fixed sentence plays. The app opens VOICE_COMMUNICATION with AEC and supplies EXTRA_AUDIO_SOURCE. Record the stop count and whether recognition was on device.", listening + BLUETOOTH_CONNECT),
    )
    fun find(id: String): Experiment? = all.find { it.id == id }
}
data class ProbeConfig(
    val number: String = "", val query: String = "jazz", val destination: String = "London",
    val appPackage: String = "", val phoneAccountIndex: Int = 0,
    val personalResultsOnLock: Boolean = false, val consents: Set<String> = emptySet(),
)
