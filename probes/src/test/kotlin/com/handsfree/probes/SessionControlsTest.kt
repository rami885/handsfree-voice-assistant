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
            session.onShow(Bundle().apply { putString("experiment", "T7") }, 0)
            assertFalse(labels(root).contains("call"))
            assertTrue(labels(root).contains("Request focus (T7)"))
        } finally { session.onDestroy(); Dispatchers.resetMain() }
    }
    private fun labels(view: View): List<String> = when (view) {
        is Button -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
}
