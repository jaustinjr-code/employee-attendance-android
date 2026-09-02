package com.jaustinjr.employeeattendance.startup

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A piece of app-lifetime coordination started once during startup on the app-lifetime scope.
 *
 * Both `LocationFeatureCoordinator::start` and `AttendanceAutoClockController::start` match this
 * shape, so they are passed to [AppStartup] as method references.
 */
fun interface StartupTask {
    fun start(scope: CoroutineScope)
}

/**
 * Startup policy: decides *when* each piece of app-lifetime coordination is allowed to begin.
 *
 * The split is not cosmetic. Work that starts a foreground service cannot run at process creation —
 * see [ForegroundGate] — while work that must observe events arriving with no screen visible (a
 * geofence broadcast waking the process, for instance) cannot be deferred to the foreground.
 */
class AppStartup(
    private val foregroundGate: ForegroundGate,
    /** Safe at process creation, and must run even when no screen is ever shown. */
    private val processCreateTasks: List<StartupTask>,
    /** Starts (directly or transitively) a foreground service, so it waits for the foreground. */
    private val foregroundTasks: List<StartupTask>,
) {

    /** Runs the startup policy. Call once, from `Application.onCreate`, on the main thread. */
    fun run(scope: CoroutineScope) {
        Log.d(TAG, "run: ${processCreateTasks.size} immediate, ${foregroundTasks.size} gated")
        processCreateTasks.forEach { it.start(scope) }
        foregroundGate.onFirstForeground {
            foregroundTasks.forEach { it.start(scope) }
        }
    }

    private companion object {
        const val TAG = "AppStartup"
    }
}

/**
 * The app-lifetime coroutine scope for startup work.
 *
 * [SupervisorJob] keeps one failing pipeline from cancelling its siblings, but it does *not* stop an
 * unhandled throw from reaching the thread's default handler and killing the process — that is how a
 * refused foreground-service start became a fatal crash (issue #49). The [CoroutineExceptionHandler]
 * is the part that degrades the feature instead of the app.
 */
fun appLifetimeScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, throwable ->
            Log.e("AppStartup", "app-lifetime coordination pipeline failed", throwable)
        },
)
