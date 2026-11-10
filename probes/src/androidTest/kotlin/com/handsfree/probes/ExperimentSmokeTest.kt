package com.handsfree.probes

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExperimentSmokeTest {
    @Test fun everyExperimentStartsAndRecords() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = (context.applicationContext as ProbesApp).controller
        for (experiment in Experiments.all) {
            val before = controller.repo.db.events().count(experiment.id)
            val activity = instrumentation.startActivitySync(Intent(context, ProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("experiment", experiment.id))
            try {
                instrumentation.runOnMainSync {
                    val start = activity.window.decorView.findViewWithTag<View>("start")
                    assertNotNull("Start button missing for ${experiment.id}", start)
                    assertTrue(start.performClick())
                }
                withTimeout(10_000) {
                    while (controller.repo.db.events().count(experiment.id) <= before) delay(20)
                }
                if (experiment.id == "T9") instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                assertTrue(controller.repo.db.events().count(experiment.id) > before)
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
