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
    private val scope by lazy {
        val controller = (application as ProbesApp).controller
        CoroutineScope(controller.scope.coroutineContext +
            SupervisorJob(controller.scope.coroutineContext.job) + controller.workerDispatcher)
    }
    private var measurement: PcmMeasurement? = null
    private var captureJob: Job? = null
    @Volatile private var record: AudioRecord? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (captureJob?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra("experiment") ?: "T3"
        val controller = (application as ProbesApp).controller
        if (checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || RECORD_AUDIO !in controller.consents) {
            controller.repo.emit(id, "pcm", "permission_or_consent_denied", outcome = "PERMISSION_DENIED"); controller.exports.automatically("microphone_denied", id); stopSelf(); return START_NOT_STICKY
        }
        val windowMs = if (id == "T3" && intent?.getBooleanExtra("screenOffMeasurement", false) == true)
            PcmMeasurement.SCREEN_OFF_WINDOW_MS else PcmMeasurement.NORMAL_WINDOW_MS
        val capture = PcmMeasurement(windowMs)
        measurement = capture
        controller.microphoneMeasurement = capture
        if (!getSystemService(android.os.PowerManager::class.java).isInteractive) capture.screenOff()
        val checkpoint = PcmCheckpoint(this)
        fun finishCapture() {
            var emitted = false
            capture.finish { event, fields ->
                emitted = true
                controller.repo.emit(id, "pcm", event, fields.toString())
            }
            if (controller.microphoneMeasurement === capture) controller.microphoneMeasurement = null
            if (emitted) controller.repo.scope.launch {
                controller.repo.snapshot()
                checkpoint.clear()
                controller.exports.automatically("microphone_end", id)
            }
        }
        try {
            checkpoint.save(id, capture)
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("probe-mic", "Microphone experiment", NotificationManager.IMPORTANCE_LOW))
            startForeground(10, Notification.Builder(this, "probe-mic").setSmallIcon(R.drawable.ic_app).setContentTitle("Microphone experiment running").setContentText("${windowMs / 1_000}-second PCM measurement; no audio is saved").build())
            controller.repo.emit(id, "fgs", "started", "assistantRole=${controller.roleHeld()}")
            captureJob = scope.launch {
                try {
                    if (checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { capture.stop("error"); controller.repo.emit(id, "pcm", "permission_denied", outcome = "PERMISSION_DENIED"); return@launch }
                    val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) { capture.stop("error"); controller.repo.emit(id, "pcm", "format_unavailable", outcome = "UNSUPPORTED"); return@launch }
                    val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum, 3_200)).build()
                    record = audio
                    val buffer = ShortArray(1_600)
                    var samples = 0L; var nonZero = 0L; var squares = 0.0
                    currentCoroutineContext().ensureActive()
                    if (id == "T3" && getSystemService(AudioManager::class.java).mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
                        capture.stop("audio_mode"); return@launch
                    }
                    if (capture.isStopped() || RECORD_AUDIO !in controller.consents) { capture.stop("consent_revoked"); return@launch }
                    audio.startRecording()
                    val start = SystemClock.elapsedRealtime()
                    var checkpointBucket = -1L
                    while (isActive && !capture.isStopped() && SystemClock.elapsedRealtime() - start < windowMs) {
                        if (RECORD_AUDIO !in controller.consents) { capture.stop("consent_revoked"); break }
                        if (id == "T3" && getSystemService(AudioManager::class.java).mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
                            capture.stop("audio_mode"); break
                        }
                        val read = audio.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        if (read < 0) { capture.stop("error"); controller.repo.emit(id, "pcm", "read_error", read.toString(), "ERROR"); break }
                        capture.add(buffer, read)
                        val bucket = capture.elapsed() / PcmMeasurement.BUCKET_MS
                        if (bucket != checkpointBucket) { checkpoint.save(id, capture); checkpointBucket = bucket }
                        for (i in 0 until read) { samples++; if (buffer[i].toInt() != 0) nonZero++; squares += buffer[i].toDouble() * buffer[i] }
                        delay(10)
                    }
                    if (isActive) capture.stop("window_complete")
                    controller.repo.emit(id, "pcm", "samples", "total=$samples;nonZero=$nonZero;rms=${if (samples > 0) sqrt(squares / samples) else 0.0};mode=${getSystemService(AudioManager::class.java).mode}")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SecurityException) { capture.stop(if (RECORD_AUDIO !in controller.consents) "consent_revoked" else "error"); controller.repo.emit(id, "pcm", "permission_denied", outcome = "PERMISSION_DENIED") }
                catch (_: IllegalStateException) { capture.stop("error"); controller.repo.emit(id, "pcm", "recording_unavailable", outcome = "UNSUPPORTED") }
                catch (error: RuntimeException) { capture.stop("error"); controller.repo.emit(id, "pcm", "platform_rejected", error.javaClass.simpleName, "UNSUPPORTED") }
                finally {
                    try { record?.let { try { it.stop() } catch (_: IllegalStateException) { }; it.release() } }
                    finally { record = null; finishCapture(); stopSelf() }
                }
            }
            captureJob?.invokeOnCompletion {
                finishCapture()
            }
        } catch (error: RuntimeException) { capture.stop("error"); controller.repo.emit(id, "fgs", "start_rejected", error.javaClass.simpleName, "DENIED");
            finishCapture()
            controller.exports.automatically("microphone_failed", id); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        measurement?.stop("service_destroyed")
        val finishing = scope.coroutineContext.job.children.toList()
        scope.cancel()
        (application as ProbesApp).controller.let { it.exports.automatically("microphone_destroyed", it.currentId, finishing) }
        record?.let { try { it.stop() } catch (_: IllegalStateException) { } }
        super.onDestroy()
    }
}
