package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import kotlinx.coroutines.*

class AudioSource private constructor(private val record: AudioRecord, private val echo: AcousticEchoCanceler?, private val pipe: Array<ParcelFileDescriptor>, private val repo: ProbeRepository, private val id: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        Os.fcntlInt(pipe[1].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        scope.launch {
            val samples = ShortArray(800); val bytes = ByteArray(1600)
            try {
                while (isActive) {
                    val count = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0) break
                    for (i in 0 until count) { bytes[i * 2] = samples[i].toByte(); bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte() }
                    var offset = 0
                    while (isActive && offset < count * 2) {
                        try { offset += Os.write(pipe[1].fileDescriptor, bytes, offset, count * 2 - offset) }
                        catch (error: ErrnoException) { if (error.errno != OsConstants.EAGAIN) break; delay(10) }
                    }
                    delay(10)
                }
            } catch (_: Exception) { repo.emit(id, "audio_source", "pipe_closed") }
        }
    }
    fun intent(): Intent {
        if (Build.VERSION.SDK_INT < 33) throw IllegalStateException("API 33 required")
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
    }
    fun close() {
        scope.cancel()
        try { record.stop() } catch (_: IllegalStateException) { }
        pipe.forEach { try { it.close() } catch (_: java.io.IOException) { } }
        echo?.release(); record.release()
    }
    companion object {
        fun create(context: Context, repo: ProbeRepository, id: String): AudioSource? {
            if (Build.VERSION.SDK_INT < 33) { repo.emit(id, "audio_source", "api_unsupported", outcome = "UNSUPPORTED"); return null }
            if (context.checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { repo.emit(id, "audio_source", "permission_denied", outcome = "PERMISSION_DENIED"); return null }
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) { repo.emit(id, "audio_source", "format_unavailable", "minBufferSize=$minimum", "UNSUPPORTED"); return null }
            val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(3_200, minimum)).build()
            var echo: AcousticEchoCanceler? = null
            var openedPipe: Array<ParcelFileDescriptor>? = null
            try {
                echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null
                echo?.enabled = true
                repo.emit(id, "audio_source", "aec", "available=${AcousticEchoCanceler.isAvailable()};enabled=${echo?.enabled};source=VOICE_COMMUNICATION")
                val pipe = ParcelFileDescriptor.createPipe()
                openedPipe = pipe
                record.startRecording()
                return AudioSource(record, echo, pipe, repo, id)
            } catch (error: Exception) { openedPipe?.forEach { try { it.close() } catch (_: java.io.IOException) { } }; echo?.release(); record.release(); throw error }
        }
    }
}
