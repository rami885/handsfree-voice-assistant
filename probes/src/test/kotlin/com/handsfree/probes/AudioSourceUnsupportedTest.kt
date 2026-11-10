package com.handsfree.probes

import android.Manifest.permission.RECORD_AUDIO
import android.media.AudioFormat
import android.media.AudioRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [UnsupportedBufferAudioRecordShadow::class])
class AudioSourceUnsupportedTest : ProbeTest() {
    @Test fun invalidMinimumBufferSizesAreRecordedBeforeReturningNull() = runTest {
        shadowOf(app).grantPermissions(RECORD_AUDIO)
        for (minimum in listOf(0, AudioRecord.ERROR, AudioRecord.ERROR_BAD_VALUE)) {
            UnsupportedBufferAudioRecordShadow.minimum = minimum
            val before = controller.repo.snapshot().count { it.experimentId == "T13" && it.step == "audio_source" }
            assertNull(AudioSource.create(app, controller.repo, "T13"))
            val records = controller.repo.snapshot().filter { it.experimentId == "T13" && it.step == "audio_source" }
            assertEquals(before + 1, records.size)
            assertEquals("format_unavailable", records.last().event)
            assertEquals("minBufferSize=$minimum", records.last().value)
            assertEquals("UNSUPPORTED", records.last().outcome)
        }
    }
}
@Implements(AudioRecord::class)
class UnsupportedBufferAudioRecordShadow {
    companion object {
        var minimum = 0
        @JvmStatic @Implementation
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int {
            check(sampleRateInHz == 16_000 && channelConfig == AudioFormat.CHANNEL_IN_MONO && audioFormat == AudioFormat.ENCODING_PCM_16BIT)
            return minimum
        }
    }
}
