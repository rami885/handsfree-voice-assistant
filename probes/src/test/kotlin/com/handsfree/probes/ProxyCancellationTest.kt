package com.handsfree.probes

import android.content.ComponentName
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ProxyCancellationTest : ProbeTest() {
    @Test fun interruptClosesRecognitionProxyDelegate() {
        val service = Robolectric.buildService(ProxyRecognitionService::class.java).create()
        val field = ProxyRecognitionService::class.java.getDeclaredField("delegate").apply { isAccessible = true }
        try {
            for (reason in listOf("consent_revoked", "call_state", "audio_mode")) {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(app, ComponentName(app, ProxyRecognitionService::class.java))
                field.set(service.get(), recognizer)
                controller.interrupt(reason)
                assertNull(reason, field.get(service.get()))
            }
            controller.setConsent(android.Manifest.permission.RECORD_AUDIO, true)
            field.set(service.get(), SpeechRecognizer.createSpeechRecognizer(app, ComponentName(app, ProxyRecognitionService::class.java)))
            controller.setConsent(android.Manifest.permission.RECORD_AUDIO, false)
            assertNull(field.get(service.get()))
            field.set(service.get(), SpeechRecognizer.createSpeechRecognizer(app, ComponentName(app, ProxyRecognitionService::class.java)))
            controller.audio.manager.mode = android.media.AudioManager.MODE_IN_CALL
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(field.get(service.get()))
        } finally { service.destroy() }
    }
}
