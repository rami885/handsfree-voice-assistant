package com.handsfree.probes

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech

@Implements(TextToSpeech::class)
class PendingTts : ShadowTextToSpeech() {
    val ids = mutableListOf<String>()
    var stops = 0
    @Implementation override fun stop(): Int { stops++; return super.stop() }
    @Implementation fun getDefaultEngine(): String = "fixture.tts"
    @Implementation
    override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        ids += utteranceId
        return TextToSpeech.SUCCESS
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [PendingTts::class])
@OptIn(ExperimentalCoroutinesApi::class)
class UtteranceLifecycleTest : ProbeTest() {
    private fun engine(): Pair<TextToSpeech, PendingTts> {
        val tts = TextToSpeech(app) { }
        ShadowTextToSpeech.addVoice(Voice("fixture", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
        AudioEngine::class.java.getDeclaredField("tts").apply { isAccessible = true }.set(controller.audio, tts)
        AudioEngine::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            .set(controller.audio, CompletableDeferred(true))
        return tts to Shadow.extract(tts)
    }
    @Test fun callbacksOnlyCompleteTheirOwnUtteranceIncludingAfterASecondStart() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (tts, pending) = engine()
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { controller.audio.speak("T4", "First sentence") }
            val initialListener = pending.utteranceProgressListener
            initialListener.onDone("stale-utterance")
            initialListener.onError("stale-utterance")
            initialListener.onStop("stale-utterance", true)
            assertFalse(first.isCompleted)
            initialListener.onDone(pending.ids.first())
            assertTrue(first.await())
            val second = async(start = CoroutineStart.UNDISPATCHED) { controller.audio.speak("T4", "Second sentence") }
            val listener = pending.utteranceProgressListener
            assertNotEquals(pending.ids[0], pending.ids[1])
            listener.onDone(pending.ids[0])
            listener.onError(pending.ids[0])
            listener.onStop(pending.ids[0], true)
            listener.onDone(null)
            assertFalse(second.isCompleted)
            listener.onDone(pending.ids[1])
            assertTrue(second.await())
            val third = async(start = CoroutineStart.UNDISPATCHED) { controller.audio.speak("T4", "Third sentence") }
            pending.utteranceProgressListener.onError(pending.ids[2])
            assertFalse(third.await())
            val fourth = async(start = CoroutineStart.UNDISPATCHED) { controller.audio.speak("T4", "Fourth sentence") }
            pending.utteranceProgressListener.onStop(pending.ids[3], true)
            assertFalse(fourth.await())
        } finally { controller.audio.stopSpeech(); runCurrent(); tts.shutdown(); Dispatchers.resetMain() }
    }
    @Test fun timeoutStopsTheTtsEngineAndClearsSpokenText() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (tts, pending) = engine()
        try {
            val speech = async(start = CoroutineStart.UNDISPATCHED) { controller.audio.speak("T4", "Pending sentence") }
            advanceTimeBy(14_999); runCurrent()
            assertFalse(speech.isCompleted)
            assertEquals(0, pending.stops)
            advanceTimeBy(1); runCurrent()
            assertFalse(speech.await())
            assertEquals(1, pending.stops)
            assertEquals("", controller.repo.spoken.value)
        } finally { controller.audio.stopSpeech(); runCurrent(); tts.shutdown(); Dispatchers.resetMain() }
    }
}
