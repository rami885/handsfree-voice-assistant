package com.handsfree.probes

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineOwnershipTest : ProbeTest() {
    private var finalizerFinished = false

    @get:Rule val checkAfterTeardown = object : TestWatcher() {
        override fun finished(description: Description) {
            assertTrue("The fixture must join its finalizer before returning from @After", finalizerFinished)
        }
    }

    @Test fun teardownKeepsTheWriterAliveUntilSuspendingControllerFinalizersFinish() {
        val repo = controller.repo
        val task = controller.scope.launch {
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    delay(1)
                    repo.emit("T12", "test", "teardown_finalizer")
                    assertTrue(repo.snapshot().any { it.event == "teardown_finalizer" })
                    finalizerFinished = true
                }
            }
        }
        testScope.runCurrent()
        assertTrue(task.isActive)
        // Leave the task running: only the fixture's @After may cancel and join it.
    }
}
