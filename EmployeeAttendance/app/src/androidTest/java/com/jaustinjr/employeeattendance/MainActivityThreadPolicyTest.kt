package com.jaustinjr.employeeattendance

import android.os.StrictMode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class MainActivityThreadPolicyTest {

    @Test
    fun launchingTheActivityDoesNoMainThreadDiskIo() {
        val violations = CopyOnWriteArrayList<String>()
        val original = StrictMode.getThreadPolicy()

        runOnMainThread {
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
