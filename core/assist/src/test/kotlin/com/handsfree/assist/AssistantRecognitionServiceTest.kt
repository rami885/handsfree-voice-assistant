package com.handsfree.assist

import android.app.Application
import android.os.RemoteException
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class AssistantRecognitionServiceTest {
    @Test fun disconnectedListenerIsCaughtAndRecordedWithoutExceptionContent() {
        val service = Robolectric.buildService(AssistantRecognitionService::class.java).create()
        try {
            service.get().rejectUnsupported { throw RemoteException("PRIVATE_LISTENER_DETAIL") }
            val entries = ShadowLog.getLogsForTag("AssistantRecognitionService")
            assertEquals(1, entries.size)
            assertEquals("recognition_client_disconnected", entries.single().msg)
            assertNull(entries.single().throwable)
            assertFalse(entries.single().msg.contains("PRIVATE_LISTENER_DETAIL"))
        } finally { service.destroy() }
    }
    @Test fun connectedListenerReceivesTheUnsupportedError() {
        val service = Robolectric.buildService(AssistantRecognitionService::class.java).create()
        try {
            var error: Int? = null
            service.get().rejectUnsupported { error = it }
            assertEquals(SpeechRecognizer.ERROR_CLIENT, error)
            assertTrue(ShadowLog.getLogsForTag("AssistantRecognitionService").isEmpty())
        } finally { service.destroy() }
    }
    @Test fun unrelatedFailuresAreNotSuppressed() {
        val service = Robolectric.buildService(AssistantRecognitionService::class.java).create()
        try {
            assertThrows(IllegalStateException::class.java) {
                service.get().rejectUnsupported { throw IllegalStateException("fixture") }
            }
        } finally { service.destroy() }
    }
}
