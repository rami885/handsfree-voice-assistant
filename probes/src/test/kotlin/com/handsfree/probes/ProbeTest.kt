package com.handsfree.probes

import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.runners.model.MultipleFailureException
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The test installs its own graph; Hilt's process-owned jobs must never start in a sandbox. */
class TestProbesApp : ProbesApp() {
    override fun onCreate() = Unit
}

@Config(application = TestProbesApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ProbeTest {
    protected val app get() = RuntimeEnvironment.getApplication() as ProbesApp
    protected val controller get() = app.controller
    protected val testDispatcher = StandardTestDispatcher()
    protected val testScope = TestScope(testDispatcher)
    protected val testScheduler get() = testScope.testScheduler
    private val lifetime = SupervisorJob(testScope.coroutineContext.job)
    private val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val controllers = mutableListOf<ProbeController>()
    private val repositories = mutableListOf<ProbeRepository>()
    private val errors = CoroutineExceptionHandler { _, error -> failures += error }

    private fun ownedScope() = CoroutineScope(SupervisorJob(lifetime) + testDispatcher + errors)

    protected fun newRepository(wrapDao: (ProbeDao) -> ProbeDao = { it }): ProbeRepository {
        val database = Room.inMemoryDatabaseBuilder(app, ProbeDatabase::class.java)
            .setQueryCoroutineContext(testDispatcher + lifetime + errors).build()
        return ProbeRepository(app, database, wrapDao(database.events()), testDispatcher, ownedScope())
            .also(repositories::add)
    }

    protected fun newController(repo: ProbeRepository): ProbeController =
        ProbeController(app, repo, ProbeExports(app, repo, dispatcher = testDispatcher),
            ownedScope(), testDispatcher).also(controllers::add)

    @Before fun createProbeGraph() {
        Dispatchers.setMain(testDispatcher)
        app.controller = newController(newRepository())
    }

    /** Drive the same scheduler from synchronous Android callbacks and assertions. */
    protected fun <T> runOnTestDispatcher(block: suspend CoroutineScope.() -> T): T {
        var result: Result<T>? = null
        runTest(testDispatcher) { result = Result.success(block()) }
        return checkNotNull(result).getOrThrow()
    }

    @After fun closeRepository() {
        try {
            testScope.runTest {
                // Finalizers may still emit results or enqueue exports. Keep the writer alive
                // until every controller (including its microphone workers) has finished.
                controllers.asReversed().forEach {
                    try { it.stop("test_cleanup") } catch (error: Throwable) { failures += error }
                    it.scope.coroutineContext.job.cancelAndJoin()
                }
                repositories.asReversed().forEach { it.scope.coroutineContext.job.cancelAndJoin() }
                repositories.asReversed().forEach { it.db.close() }
                lifetime.cancelAndJoin()
            }
        } catch (error: Throwable) {
            failures += error
        } finally {
            lifetime.cancel()
            testScope.cancel()
            repositories.asReversed().forEach {
                try { it.db.close() } catch (error: Throwable) { failures += error }
            }
            testScheduler.runCurrent()
            Dispatchers.resetMain()
        }
        // Report failures in the owning test, including exceptions thrown by cancellation
        // finalizers. They must never reach the global coroutine-test exception collector.
        MultipleFailureException.assertEmpty(failures)
    }
}
