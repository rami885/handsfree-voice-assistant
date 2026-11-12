package com.handsfree.probes

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionControlsTest : ProbeTest() {
    @Test fun reusedSessionRefreshesItsExperimentButtons() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val session = ProbeSession(app)
        try {
            val root = session.onCreateContentView()
            session.onShow(Bundle().apply { putString("experiment", "T2") }, 0)
            assertTrue(labels(root).contains("call"))
            session.onShow(Bundle().apply { putString("experiment", "T5") }, 0)
            assertFalse(labels(root).contains("call"))
            assertTrue(labels(root).contains("WhatsApp Answer"))
            assertTrue(labels(root).contains("WhatsApp Decline"))
            session.onShow(Bundle().apply { putString("experiment", "T10") }, 0)
            assertTrue(labels(root).contains("navigation"))
            assertTrue(labels(root).contains("read"))
            assertTrue(labels(root).contains("send"))
            assertFalse(labels(root).contains("WhatsApp Answer"))
            assertFalse(labels(root).contains("play"))
            session.onShow(Bundle().apply { putString("experiment", "T7") }, 0)
            assertFalse(labels(root).contains("call"))
            assertTrue(labels(root).contains("Request focus (T7)"))
        } finally { session.onDestroy(); Dispatchers.resetMain() }
    }
    @Test fun lockSessionButtonsEvaluateBothPersonalActionsWithoutLaunchingAnActivity() {
        controller.start("T10", ProbeConfig())
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        val session = ProbeSession(app)
        try {
            val root = session.onCreateContentView()
            controller.session = session
            fun buttons(view: View): List<Button> = when (view) {
                is Button -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                else -> emptyList()
            }
            for (kind in listOf("read", "send")) {
                buttons(root).single { it.text == kind }.performClick()
                testScheduler.runCurrent()
                assertTrue(controller.repo.lines.value.any { "\"action\":\"$kind\"" in it && "Deny(LOCKED)" in it })
            }
            assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
            assertFalse(controller.repo.lines.value.any { "launch_requested" in it })
        } finally { session.onDestroy() }
    }
    private fun labels(view: View): List<String> = when (view) {
        is Button -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
}
