package com.jaustinjr.employeeattendance

import android.app.Application
import com.jaustinjr.employeeattendance.di.AppContainer
import com.jaustinjr.employeeattendance.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point that owns the app-scoped [AppContainer]. Screens and ViewModels reach
 * their dependencies through this container rather than constructing them ad hoc.
 *
 * ## Startup threading (issue #19)
 * [onCreate] runs on the main thread before any window exists, so it must not touch disk or the
 * Android Keystore. Four of the container's singletons are backed by `EncryptedSharedPreferences`
 * (Keystore key unwrap + file I/O + JSON decode each), so the feature wiring that pulls them in is
 * moved onto [applicationScope] (`Dispatchers.IO`) as [startupJob].
 *
 * ### Why this is still ordering-safe
 * 1. `DefaultAppContainer(this)` itself stays on the main thread. It only allocates the object;
 *    every dependency inside it is `by lazy`, so no disk or crypto work happens here. That keeps
 *    `container` assigned before [onCreate] returns, so no component can ever observe the
 *    `lateinit` unset — which is exactly the crash a background assignment would risk.
 * 2. Kotlin's default `by lazy` is `LazyThreadSafetyMode.SYNCHRONIZED`, so a UI read racing the
 *    background wiring blocks until construction finishes and still sees a single instance. The
 *    worst case is a short wait that used to be unconditionally on the main thread.
 * 3. The proximity event stream is a `MutableSharedFlow(replay = 0)`: an event emitted while no
 *    collector is attached is dropped, and a dropped `Arrived`/`Departed` is a missed clock-in.
 *    So [startupJob] attaches the consumer FIRST and waits (via
 *    [com.jaustinjr.employeeattendance.attendance.AttendanceAutoClockController.awaitSubscribed])
 *    for the subscription to actually be live before starting the producer pipelines. Doing it in
 *    the other order — as the pre-fix code did — leaves a window in which the coordinator's
 *    foreground proximity pipeline can emit into a flow nobody is listening to.
 * 4. The other producer, [com.jaustinjr.employeeattendance.location.geofence.GeofenceBroadcastReceiver],
 *    is driven by the OS and can fire at any moment, including on a cold start whose only purpose
 *    is delivering that broadcast. It cannot be ordered by us, so it orders itself: it calls
 *    [awaitStarted] before forwarding the transition. See that class for details.
 */
class EmployeeAttendanceApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** App-lifetime scope for coordination that must run regardless of any screen being visible. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Completes once the proximity event consumer is subscribed and the location pipelines are
     * running. Producers that the app does not control (OS broadcasts) join on this before
     * emitting; see [awaitStarted].
     */
    private lateinit var startupJob: Job

    override fun onCreate() {
        super.onCreate()
        // Allocation only — every dependency inside is `by lazy`, so this does no I/O.
        container = DefaultAppContainer(this)
        // Dispatchers.IO: constructing the stores is blocking disk + Keystore work, which must not
        // occupy a Default worker (those are sized for CPU work and are what the collectors below
        // run on).
        startupJob = applicationScope.launch(Dispatchers.IO) {
            // Consumer before producers: proximity events are a replay-0 SharedFlow, so anything
            // emitted before this collector is subscribed would be silently dropped, losing a
            // hands-off clock in/out. Consume proximity Arrived/Departed to drive auto clock
            // in/out (the Worksite feature); runs for the whole process so it works with no screen
            // visible.
            val autoClock = container.attendanceAutoClockController
            autoClock.start(applicationScope)
            // `start` launches a coroutine, so the collector is not attached when it returns. Wait
            // for the subscription to be live before anything can emit into the flow.
            autoClock.awaitSubscribed()
            // Now start the location feature's reactive coordination for the life of the process.
            container.locationFeatureCoordinator.start(applicationScope)
        }
    }

    /**
     * Suspends until app startup wiring has completed. Callers that can be invoked by the platform
     * before [onCreate]'s background wiring finishes — notably geofence broadcasts, which Android
     * may use to cold-start the process — must await this before producing proximity events, so the
     * event isn't emitted into a flow that has no subscriber yet.
     *
     * Startup is a handful of prefs reads, so this returns in milliseconds; it is well inside a
     * broadcast receiver's time budget when combined with `goAsync()`.
     */
    suspend fun awaitStarted() {
        // join() rather than await(): a failed startup must not be rethrown into an unrelated
        // caller such as a broadcast receiver. Failures are already logged where they happen.
        startupJob.join()
    }
}
