package com.handsfree.probes

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.window.OnBackInvokedCallback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWindowOnBackInvokedDispatcher
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class ProbeBackNavigationTest : ProbeTest() {
    private fun top(activity: ProbeActivity): OnBackInvokedCallback? {
        // Registrations move from the Activity's proxy to its window on attachment.
        val actual = ReflectionHelpers.getField<Any?>(activity.onBackInvokedDispatcher, "mActualDispatcher")
        if (actual != null) return ReflectionHelpers.callInstanceMethod(actual, "getTopCallback")
        val callbacks = ReflectionHelpers.getField<List<android.util.Pair<OnBackInvokedCallback, Int>>>(
            activity.onBackInvokedDispatcher, "mCallbacks")
        return callbacks.maxByOrNull { it.second }?.first
    }

    private fun enablePredictiveBack() {
        // Robolectric does not copy the manifest opt-in into ApplicationInfo.
        ReflectionHelpers.callInstanceMethod<Void>(app.applicationInfo, "setEnableOnBackInvokedCallback",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        ReflectionHelpers.callStaticMethod<Void>(ShadowWindowOnBackInvokedDispatcher::class.java, "setEnablePredictiveBack",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    @Test fun systemCallbackReturnsToListAndUnregistersWhileListUsesDefaultBack() {
        enablePredictiveBack()
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup()
        try {
            val listCallback = top(activity.get())
            assertTrue(buttons(activity.get().window.decorView).any { it.tag == "T7" })
            activity.get().show("T7")
            val experimentCallback = top(activity.get())
            assertNotNull(experimentCallback)
            assertNotSame(listCallback, experimentCallback)
            assertTrue(buttons(activity.get().window.decorView).any { it.text == "← Back to experiments" })
            experimentCallback!!.onBackInvoked()
            assertFalse(activity.get().isFinishing)
            assertTrue(buttons(activity.get().window.decorView).any { it.tag == "T7" })
            assertSame(listCallback, top(activity.get()))
            assertEquals("T7", controller.currentId)
            activity.get().show("T2")
            buttons(activity.get().window.decorView).single { it.text == "← Back to experiments" }.performClick()
            assertSame(listCallback, top(activity.get()))
            assertEquals("T2", controller.currentId)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun rotationKeepsExperimentAndBackCallback() {
        enablePredictiveBack()
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup()
        try {
            activity.get().show("T7")
            activity.recreate()
            assertEquals("T7", controller.currentId)
            assertTrue(buttons(activity.get().window.decorView).any { it.text == "← Back to experiments" })
            top(activity.get())!!.onBackInvoked()
            assertTrue(buttons(activity.get().window.decorView).any { it.tag == "T7" })
            assertFalse(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun processRecreationSelectsSavedScreenAndDoesNotRestoreLaunchScreenAfterListReturn() {
        enablePredictiveBack()
        val launch = Intent(app, ProbeActivity::class.java).putExtra("experiment", "T3")
        val original = Robolectric.buildActivity(ProbeActivity::class.java, launch).setup()
        original.get().show("T7")
        val state = Bundle()
        original.pause().saveInstanceState(state).stop().destroy()
        app.controller = newController(newRepository()) // Simulate a fresh process graph.
        val restored = Robolectric.buildActivity(ProbeActivity::class.java, launch).create(state).start().resume().visible()
        val listState = Bundle()
        try {
            assertEquals("T7", controller.currentId)
            assertFalse(controller.repo.lines.value.any { " / start / " in it })
            top(restored.get())!!.onBackInvoked()
            restored.pause().saveInstanceState(listState).stop()
        } finally { restored.destroy() }
        val list = Robolectric.buildActivity(ProbeActivity::class.java, launch).create(listState).start().resume().visible()
        try {
            assertTrue(buttons(list.get().window.decorView).any { it.tag == "T3" })
            assertFalse(buttons(list.get().window.decorView).any { it.text == "← Back to experiments" })
            assertEquals("T7", controller.currentId)
        } finally { list.pause().stop().destroy() }
    }

    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32])
class ProbeLegacyBackNavigationTest : ProbeTest() {
    @Suppress("DEPRECATION")
    @Test fun legacySystemBackReturnsToListThenFinishesNormally() {
        val activity = Robolectric.buildActivity(ProbeActivity::class.java).setup()
        try {
            activity.get().show("T7")
            activity.get().onBackPressed()
            assertFalse(activity.get().isFinishing)
            val state = Bundle()
            activity.saveInstanceState(state)
            assertNull(state.getString("screen"))
            assertEquals("T7", controller.currentId)
            activity.get().onBackPressed()
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }
}
