package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.os.Bundle
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class EmptyWindowsTest : ProbeTest() {
    @Test fun warningsRequireTwoFreshEmptyWindowsAndResetOnEveryExperimentChange() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        shadowOf(app).grantPermissions(RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val speech = SpeechEngine(app, controller.repo) { true }
        val warnings = mutableListOf<String>()
        speech.onEmptyWindows = { warnings += it }
        suspend fun window(id: String, text: String? = null) {
            val result = async(start = CoroutineStart.UNDISPATCHED) { speech.listen(id, defaultClient = true) }
            assertTrue(speech.isListening)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            val bundle = Bundle().apply {
                if (text != null) putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            }
            shadowOf(recognizer).triggerOnResults(bundle)
            result.await()
        }
        try {
            window("T1a"); assertTrue(warnings.isEmpty())
            window("T1a"); assertEquals(listOf("T1a"), warnings)
            window("T1a"); assertEquals(1, warnings.size)
            window("T1a"); assertEquals(2, warnings.size)
            window("T1a")
            window("T2"); assertEquals(2, warnings.size)
            window("T2"); assertEquals(listOf("T1a", "T1a", "T2"), warnings)
            window("T2")
            window("T2", "hello")
            window("T2"); assertEquals(3, warnings.size)
            // A change without listening (e.g. T9) must also clear the previous count.
            controller.speech.experimentChanged("T1a")
            val counter = SpeechEngine::class.java.getDeclaredField("emptyWindows").apply { isAccessible = true }
            counter.setInt(controller.speech, 1)
            controller.start("T9", ProbeConfig())
            assertEquals(0, counter.getInt(controller.speech))
        } finally { speech.close("test"); Dispatchers.resetMain() }
    }
}
