package com.jaustinjr.employeeattendance.startup

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Platform half of the issue #49 regression cover. [AppStartupTest] pins the ordering policy against
 * a fake gate; this pins the real gate against a real [LifecycleRegistry] — the JVM layer cannot,
 * because `LifecycleRegistry` enforces main-thread access through a `Looper`.
 *
 * What must hold: no callback before `STARTED` (that window is where the crash lived), a callback at
 * `STARTED`, and never a second one.
 */
@RunWith(AndroidJUnit4::class)
class ProcessLifecycleForegroundGateTest {

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun doesNotFireBeforeTheLifecycleIsStarted() {
        var fireCount = 0

        onMain {
            val owner = FakeOwner()
            ProcessLifecycleForegroundGate { owner.lifecycle }.onFirstForeground { fireCount++ }
            // CREATED is the state the process is in during Application.onCreate — the exact point
            // where the old code called startForegroundService() and the system refused.
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        assertEquals("gated work ran while the process was not foreground", 0, fireCount)
    }

    @Test
    fun firesOnceTheLifecycleReachesStarted() {
        var fireCount = 0

        onMain {
            val owner = FakeOwner()
            ProcessLifecycleForegroundGate { owner.lifecycle }.onFirstForeground { fireCount++ }
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        assertEquals(1, fireCount)
    }

    @Test
    fun firesOnlyOnceAcrossRepeatedForegroundReturns() {
        var fireCount = 0

        onMain {
            val owner = FakeOwner()
            ProcessLifecycleForegroundGate { owner.lifecycle }.onFirstForeground { fireCount++ }
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        assertEquals("the gate re-ran startup on a foreground return", 1, fireCount)
    }

    @Test
    fun observingAnAlreadyStartedLifecycleFiresImmediately() {
        var fireCount = 0

        onMain {
            val owner = FakeOwner()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            // Lifecycle replays state to a newly added observer, so a gate installed after the app
            // is already foreground still releases its work rather than waiting forever.
            ProcessLifecycleForegroundGate { owner.lifecycle }.onFirstForeground { fireCount++ }
        }

        assertEquals(1, fireCount)
    }

    @Test
    fun theDefaultGateObservesTheRealProcessLifecycleOnTheMainThread() {
        val fired = CountDownLatch(1)
        var deliveredOnMain = false

        // No lifecycleProvider override: this is the production wiring. The instrumentation process
        // is foreground, so ProcessLifecycleOwner is already STARTED and must release immediately.
        onMain {
            ProcessLifecycleForegroundGate().onFirstForeground {
                deliveredOnMain = Looper.myLooper() == Looper.getMainLooper()
                fired.countDown()
            }
        }

        assertTrue(
            "the real process gate never released; startup work would silently never run",
            fired.await(5, TimeUnit.SECONDS),
        )
        assertTrue("gated work was not delivered on the main thread", deliveredOnMain)
    }
}
