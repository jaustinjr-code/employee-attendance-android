package com.jaustinjr.employeeattendance

import android.os.StrictMode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression test for issue #58: launching the real [MainActivity] must not do disk
 * I/O on the main thread.
 *
 * [StartupThreadPolicyTest] covers `Application.onCreate`'s wiring, but it replays that wiring
 * directly and never constructs a `ViewModel`. The stall this test targets lives one layer up —
 * `LocationViewModel.Factory` and friends dereference `EncryptedSharedPreferences`-backed
 * repositories during composition — so it has to drive the Activity itself.
 *
 * ### Read this before trusting a green run
 * The instrumentation process is already warm when this executes: the application object was
 * created before the test method ran, so `startupJob` has usually finished and the container's
 * lazies are already populated. In that state the factories perform no disk I/O whether or not the
 * gate exists, and **this test passes vacuously**.
 *
 * It is therefore a guard against regression in the steady state, not proof that the cold-start
 * race is closed. The gate's actual invariant — that gated content is not composed before startup
 * completes — is pinned by
 * [com.jaustinjr.employeeattendance.ui.main.StartupGateTest], which does not depend on timing.
 * Reproducing the true cold-start race under instrumentation would need the process killed and the
 * app's data cleared between the container being created and the Activity launching, which the test
 * runner does not offer a hook for.
 *
 * Not run in CI on a JVM-only job (needs a device); run with `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
// StrictMode.ThreadPolicy.Builder.penaltyListener(Executor, OnThreadViolationListener) and
// StrictMode.Violation are API 28; minSdk is 24. Without this the test throws NoSuchMethodError on
// a 24-27 device. Kotlin compilation does not enforce API levels and lint's checkTestSources is off
// by default, so nothing else catches it.
@SdkSuppress(minSdkVersion = 28)
class MainActivityThreadPolicyTest {

    @Test
    fun launchingTheActivityDoesNoMainThreadDiskIo() {
        val violations = CopyOnWriteArrayList<String>()

        // ThreadPolicy is per-thread state, so the policy to restore has to be read on the SAME
        // thread it will be restored to. Capturing it out here would read the instrumentation
        // thread's policy and then install that on main in the finally block, silently replacing
        // main's real policy for the rest of the instrumentation process -- an order-dependent
        // failure for any later test that depends on it (StartupThreadPolicyTest is in this very
        // source set doing StrictMode work).
        lateinit var original: StrictMode.ThreadPolicy
        runOnMainThread {
            original = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyListener(Runnable::run) { violation ->
                        violations += violation.stackTraceToString()
                    }
                    .build(),
            )
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                // Let composition and any queued main-thread work settle before asserting.
                runOnMainThread { }
            }

            assertTrue(
                "main-thread disk I/O while launching MainActivity:\n" +
                    violations.joinToString("\n\n"),
                violations.isEmpty(),
            )
        } finally {
            runOnMainThread { StrictMode.setThreadPolicy(original) }
        }
    }

    private fun runOnMainThread(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }
}
