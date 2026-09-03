package com.jaustinjr.employeeattendance.startup

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression cover for issue #49: the app crashed on every cold start because
 * `LocationFeatureCoordinator.start` — which reaches `startForegroundService()` — ran from
 * `Application.onCreate()`, while the process was still `PROCESS_STATE_CACHED_EMPTY`.
 *
 * These tests pin the two halves of the fix that are policy rather than platform: the ordering
 * guarantee, and the fact that the app-lifetime scope handles rather than propagates a throw.
 * The platform half (that `ProcessLifecycleOwner` really fires only once an Activity is STARTED)
 * is covered in `androidTest`.
 */
class AppStartupTest {

    /** A [ForegroundGate] that never fires unless a test explicitly releases it. */
    private class ManualForegroundGate : ForegroundGate {
        private val actions = mutableListOf<() -> Unit>()
        val pendingCount: Int get() = actions.size

        override fun onFirstForeground(action: () -> Unit) {
            actions += action
        }

        fun reachForeground() {
            val pending = actions.toList()
            actions.clear()
            pending.forEach { it() }
        }
    }

    private class RecordingTask : StartupTask {
        var startCount = 0
        var scope: CoroutineScope? = null
        override fun start(scope: CoroutineScope) {
            startCount++
            this.scope = scope
        }
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Test
    fun `foreground work does not run at process creation`() {
        val gate = ManualForegroundGate()
        val gated = RecordingTask()

        AppStartup(gate, processCreateTasks = emptyList(), foregroundTasks = listOf(gated))
            .run(scope)

        // This is the crash: before the fix this task ran here, and startForegroundService() threw.
        assertEquals(0, gated.startCount)
        assertEquals(1, gate.pendingCount)
    }

    @Test
    fun `foreground work runs once the process reaches the foreground`() {
        val gate = ManualForegroundGate()
        val gated = RecordingTask()

        AppStartup(gate, processCreateTasks = emptyList(), foregroundTasks = listOf(gated))
            .run(scope)
        gate.reachForeground()

        assertEquals(1, gated.startCount)
        assertSame(scope, gated.scope)
    }

    @Test
    fun `process-create work runs immediately, without waiting for the foreground`() {
        val gate = ManualForegroundGate()
        val immediate = RecordingTask()

        AppStartup(gate, processCreateTasks = listOf(immediate), foregroundTasks = emptyList())
            .run(scope)

        // Proximity event consumption must be listening when a geofence broadcast wakes a process
        // that never shows a screen, so it must not sit behind the gate.
        assertEquals(1, immediate.startCount)
    }

    @Test
    fun `both task groups get the same app-lifetime scope`() {
        val gate = ManualForegroundGate()
        val immediate = RecordingTask()
        val gated = RecordingTask()

        AppStartup(gate, listOf(immediate), listOf(gated)).run(scope)
        gate.reachForeground()

        assertSame(scope, immediate.scope)
        assertSame(scope, gated.scope)
    }

    @Test
    fun `the app-lifetime scope carries a CoroutineExceptionHandler`() {
        assertNotNull(
            "Without a handler an unhandled throw reaches the default handler and kills the process",
            appLifetimeScope().coroutineContext[CoroutineExceptionHandler],
        )
    }

    @Test
    fun `a throwing pipeline is handled instead of reaching the process-killing default handler`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val escaped = AtomicReference<Throwable?>(null)
        Thread.setDefaultUncaughtExceptionHandler { _, t -> escaped.set(t) }
        try {
            val appScope = appLifetimeScope()
            val survived = CountDownLatch(1)

            // Shaped like the real crash: an unchecked throw from a pipeline on Dispatchers.Default.
            val failing = appScope.launch { throw IllegalStateException("startForegroundService() not allowed") }
            val sibling = appScope.launch { survived.countDown() }
            runBlocking { failing.join(); sibling.join() }

            // Without the CoroutineExceptionHandler this is exactly the path that killed the app:
            // coroutines falls back to the thread's uncaught handler, which terminates the process.
            assertNull("the failure escaped to the default uncaught handler", escaped.get())
            assertTrue("sibling pipeline did not survive", survived.await(5, TimeUnit.SECONDS))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
