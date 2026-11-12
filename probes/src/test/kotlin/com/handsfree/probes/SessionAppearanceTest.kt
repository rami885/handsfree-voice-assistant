package com.handsfree.probes

import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionAppearanceTest : ProbeTest() {
    @Test @Config(qualifiers = "notnight") fun lightContentIsOpaqueAndReadable() = checkContent(Color.WHITE, Color.BLACK)
    @Test @Config(qualifiers = "night") fun darkContentIsOpaqueAndReadable() = checkContent(Color.rgb(18, 18, 18), Color.WHITE)

    private fun checkContent(background: Int, foreground: Int) {
        val session = ProbeSession(app)
        try {
            val root = session.onCreateContentView() as ScrollView
            assertEquals(background, (root.background as ColorDrawable).color)
            assertEquals(255, Color.alpha((root.background as ColorDrawable).color))
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, root.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, root.layoutParams.height)
            assertTrue(root.isFillViewport)
            val text = texts(root)
            assertTrue(text.isNotEmpty())
            assertTrue(text.all { it.currentTextColor == foreground })
            val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
            keyguard.setKeyguardLocked(false)
            controller.repo.emit("T10", "manual", "observation", "private value")
            testScheduler.runCurrent()
            assertTrue(text.any { "private value" in it.text })
            keyguard.setKeyguardLocked(true)
            controller.repo.emit("T10", "manual", "observation", "second private value")
            testScheduler.runCurrent()
            assertFalse(text.any { "private value" in it.text })
            assertTrue(text.any { "T10 / manual / observation" in it.text })
        } finally { session.onDestroy() }
    }
    private fun texts(view: View): List<TextView> = when (view) {
        is Button -> emptyList()
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
