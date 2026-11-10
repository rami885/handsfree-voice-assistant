package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlin.math.sqrt

class MicrophoneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("experiment") ?: "T3"
        val controller = (application as ProbesApp).controller
        if (checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || RECORD_AUDIO !in controller.config.consents) {
            controller.repo.emit(id, "pcm", "permission_or_consent_denied", outcome = "PERMISSION_DENIED"); stopSelf(); return START_NOT_STICKY
        }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("probe-mic", "Microphone experiment", NotificationManager.IMPORTANCE_LOW))
            startForeground(10, Notification.Builder(this, "probe-mic").setSmallIcon(R.drawable.ic_app).setContentTitle("Microphone experiment running").setContentText("Three-second PCM measurement; no audio is saved").build())
            controller.repo.emit(id, "fgs", "started", "assistantRole=${controller.roleHeld()}")
            scope.launch {
                try {
                    if (checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { controller.repo.emit(id, "pcm", "permission_denied", outcome = "PERMISSION_DENIED"); return@launch }
                    val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) { controller.repo.emit(id, "pcm", "format_unavailable", outcome = "UNSUPPORTED"); return@launch }
                    val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum, 3_200)).build()
                    record = audio
                    val buffer = ShortArray(1_600)
                    var samples = 0L; var nonZero = 0L; var squares = 0.0
                    audio.startRecording()
                    val start = SystemClock.elapsedRealtime()
                    while (isActive && SystemClock.elapsedRealtime() - start < 3_000) {
                        val read = audio.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        if (read < 0) { controller.repo.emit(id, "pcm", "read_error", read.toString(), "ERROR"); break }
                        for (i in 0 until read) { samples++; if (buffer[i].toInt() != 0) nonZero++; squares += buffer[i].toDouble() * buffer[i] }
                        delay(10)
                    }
                    controller.repo.emit(id, "pcm", "samples", "total=$samples;nonZero=$nonZero;rms=${if (samples > 0) sqrt(squares / samples) else 0.0};mode=${getSystemService(AudioManager::class.java).mode}")
                } catch (_: SecurityException) { controller.repo.emit(id, "pcm", "permission_denied", outcome = "PERMISSION_DENIED") }
                catch (_: IllegalStateException) { controller.repo.emit(id, "pcm", "recording_unavailable", outcome = "UNSUPPORTED") }
                catch (error: RuntimeException) { controller.repo.emit(id, "pcm", "platform_rejected", error.javaClass.simpleName, "UNSUPPORTED") }
                finally { record?.let { try { it.stop() } catch (_: IllegalStateException) { }; it.release() }; record = null; stopSelf() }
            }
        } catch (error: RuntimeException) { controller.repo.emit(id, "fgs", "start_rejected", error.javaClass.simpleName, "DENIED"); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
