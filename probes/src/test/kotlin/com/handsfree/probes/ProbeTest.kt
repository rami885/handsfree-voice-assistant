package com.handsfree.probes

import kotlinx.coroutines.*
import org.junit.After
import org.robolectric.RuntimeEnvironment

abstract class ProbeTest {
    protected val app get() = RuntimeEnvironment.getApplication() as ProbesApp
    protected val controller get() = app.controller
    @After fun closeRepository() {
        controller.stop("test_cleanup")
        controller.scope.cancel()
        runBlocking { controller.repo.scope.coroutineContext[Job]?.cancelAndJoin() }
        controller.repo.db.close()
    }
}
