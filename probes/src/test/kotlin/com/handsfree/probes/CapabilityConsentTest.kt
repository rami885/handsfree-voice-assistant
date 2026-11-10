package com.handsfree.probes

import android.Manifest.permission.*
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CapabilityConsentTest : ProbeTest() {
    @Test fun checkboxesUpdateOnlyTheirCapabilityAcrossScreensAndStarts() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).create().get()
        fun boxes(view: View): List<CheckBox> = when (view) {
            is CheckBox -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { boxes(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun box(permission: String) = boxes(activity.window.decorView).single {
            it.text.toString() == "I consent to using ${permission.substringAfterLast('.')}"
        }
        try {
            activity.show("T1a")
            box(RECORD_AUDIO).isChecked = true
            box(READ_PHONE_STATE).isChecked = true
            activity.show("T4")
            box(BLUETOOTH_CONNECT).isChecked = true
            assertTrue(RECORD_AUDIO in controller.consents)
            assertTrue(READ_PHONE_STATE in controller.consents)
            controller.start("T9", ProbeConfig())
            assertTrue(RECORD_AUDIO in controller.consents)
            assertTrue(BLUETOOTH_CONNECT in controller.consents)
            activity.show("T1b")
            assertTrue(box(RECORD_AUDIO).isChecked)
            assertTrue(box(BLUETOOTH_CONNECT).isChecked)
            var interrupted = false
            val interrupt = { interrupted = true }
            controller.registerRecognitionInterruptor(interrupt)
            box(RECORD_AUDIO).isChecked = false
            assertTrue(interrupted)
            assertFalse(RECORD_AUDIO in controller.consents)
            assertTrue(BLUETOOTH_CONNECT in controller.consents)
            assertTrue(READ_PHONE_STATE in controller.consents)
            controller.unregisterRecognitionInterruptor(interrupt)
        } finally { activity.finish() }
    }
}
