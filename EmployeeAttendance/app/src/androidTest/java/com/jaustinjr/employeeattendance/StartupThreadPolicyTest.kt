package com.jaustinjr.employeeattendance

import android.content.Context
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaustinjr.employeeattendance.di.DefaultAppContainer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression test for issue #19: the work `Application.onCreate()` triggers must not
 * do disk reads/writes on the main thread.
 *
 * Mirrors what `EmployeeAttendanceApplication.onCreate()` does — construct the container on the
 * main thread, then do the feature wiring on a background scope — while a `StrictMode.ThreadPolicy`
 * with `detectDiskReads()`/`detectDiskWrites()` and a `penaltyListener` is installed on the main
 * thread. Against the pre-fix ordering (wiring done inline on the calling thread) this records
 * violations from `EncryptedSharedPreferences` construction; after the fix it records none.
 *
 * Not run in CI (no device attached); run with `./gradlew connectedDebugAndroidTest` on a device or
 * emulator.
 */
@RunWith(AndroidJUnit4::class)
class StartupThreadPolicyTest {

    @Test
    fun containerWiringDoesNoMainThreadDiskIo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val violations = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val done = CountDownLatch(1)

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
            // Step 1 — on the main thread, exactly as onCreate() does it. Allocation only.
            var container: DefaultAppContainer? = null
            runOnMainThread { container = DefaultAppContainer(context) }
            val appContainer = requireNotNull(container)

            // Step 2 — the feature wiring, on a background scope. This is what pulls in the four
            // EncryptedSharedPreferences-backed stores.
            scope.launch(Dispatchers.IO) {
                val autoClock = appContainer.attendanceAutoClockController
                autoClock.start(scope)
                autoClock.awaitSubscribed()
                appContainer.locationFeatureCoordinator.start(scope)
                done.countDown()
            }
            assertTrue("startup wiring did not finish", done.await(30, TimeUnit.SECONDS))

            // Let any queued main-thread work (and its violations) settle before asserting.
            runOnMainThread { }

            assertTrue(
                "main-thread disk I/O during startup wiring:\n${violations.joinToString("\n\n")}",
                violations.isEmpty(),
            )
        } finally {
            runOnMainThread { StrictMode.setThreadPolicy(original) }
            scope.cancel()
        }
    }

    private fun runOnMainThread(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }
}
