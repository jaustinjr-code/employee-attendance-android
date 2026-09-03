package com.jaustinjr.employeeattendance

import android.app.Application
import android.util.Log
import com.jaustinjr.employeeattendance.di.AppContainer
import com.jaustinjr.employeeattendance.di.DefaultAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application entry point that owns the app-scoped [AppContainer]. Screens and ViewModels reach
 * their dependencies through this container rather than constructing them ad hoc.
 *
 * ## Startup threading (issue #19)
 * [onCreate] runs on the main thread before any window exists, so it must not touch disk or the
 * Android Keystore. Five of the container's singletons are backed by `EncryptedSharedPreferences`
 * (Keystore key unwrap + file I/O + JSON decode each), so the feature wiring that pulls them in is
 * moved onto [applicationScope] (`Dispatchers.IO`) as [startupJob].
 *
 * ### Why this is still ordering-safe
 * 1. `DefaultAppContainer(this)` itself stays on the main thread. It only allocates the object;
 *    every dependency inside it is `by lazy`, so no disk or crypto work happens here. That keeps
 *    `container` assigned before [onCreate] returns, so no component can ever observe the
 *    `lateinit` unset — which is exactly the crash a background assignment would risk.
 * 2. Kotlin's default `by lazy` is `LazyThreadSafetyMode.SYNCHRONIZED`, so a UI read racing the
 *    background wiring blocks until construction finishes and still sees a single instance --
 *    never a duplicate or a half-built object.
 *
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
 *
 * ## The UI is gated too (issue #58)
 * Moving the Application's wiring off the main thread was not sufficient on its own. The ViewModel
 * factories dereference `container` repositories during `MainActivity`'s composition, microseconds
 * after [onCreate] returns and while [startupJob] is still constructing those same stores on IO.
 * Because the lazies are `SYNCHRONIZED`, main would block on a monitor held by an IO worker for the
 * length of a Keystore unwrap plus `SecurePreferences.migratePlaintext`'s synchronous `commit()`s —
 * a priority inversion, since main is priority-boosted and the IO worker is not, so the stall could
 * exceed the cost of just doing the work inline.
 *
 * A `ViewModelProvider.Factory` cannot suspend, so the fix is on the UI side: `MainActivity`
 * observes [startupComplete] and renders a loading state until the wiring settles, constructing no
 * ViewModel before then. The main thread stays free to render instead of blocking.
 *
 * The guarantee only holds for stores [startupJob] actually forces, so it forces **all five**
 * `EncryptedSharedPreferences`-backed ones. Four come in transitively via the wiring above
 * (`attendanceRepository`, `workLocationRepository`, `proximityRepository`,
 * `clockNotificationSettingsStore`); `privacySettingsStore` does not, and is forced explicitly —
 * without that, `SettingsViewModel.Factory` and `WorksiteRegistrationViewModel.Factory` would still
 * construct it on the main thread during a navigation transition, after the gate had opened. The
 * container's remaining dependencies (`locationTracker`, `addressGeocoder`, `addressAutocomplete`)
 * touch no disk and need no warm-up.
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

    private val _startupComplete = MutableStateFlow(false)

    /**
     * Whether [startupJob] has reached a terminal state, for UI that must not construct
     * container-backed dependencies before then (issue #58).
     *
     * Distinct from [awaitStarted] because a `ViewModelProvider.Factory` cannot suspend: the UI
     * observes this instead and holds a loading state, so the main thread stays free to render
     * rather than blocking on a `by lazy` monitor held by the startup IO worker.
     *
     * Becomes `true` on **any** terminal state — success, failure, or cancellation — so a failed
     * startup degrades to "automatic clock in/out is off" rather than pinning the UI on a loading
     * screen forever. The stores are constructed by then either way, so the factories' reads are
     * cached-value returns rather than contended construction.
     */
    val startupComplete: StateFlow<Boolean> = _startupComplete.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        // Allocation only — every dependency inside is `by lazy`, so this does no I/O.
        container = DefaultAppContainer(this)
        // Dispatchers.IO: constructing the stores is blocking disk + Keystore work, which must not
        // occupy a Default worker (those are sized for CPU work and are what the collectors below
        // run on).
        startupJob = applicationScope.launch(Dispatchers.IO) {
            try {
                // Consumer before producers: proximity events are a replay-0 SharedFlow, so
                // anything emitted before this collector is subscribed would be silently dropped,
                // losing a hands-off clock in/out. Consume proximity Arrived/Departed to drive
                // auto clock in/out (the Worksite feature); runs for the whole process so it works
                // with no screen visible.
                val autoClock = container.attendanceAutoClockController
                autoClock.start(applicationScope)
                // `start` launches a coroutine, so the collector is not attached when it returns.
                // Wait for the subscription to be live before anything can emit into the flow.
                autoClock.awaitSubscribed()
                // Now start the location feature's coordination for the process lifetime.
                container.locationFeatureCoordinator.start(applicationScope)
                // Force the one remaining EncryptedSharedPreferences-backed store that the wiring
                // above does not pull in. Nothing here needs it, but SettingsViewModel.Factory and
                // WorksiteRegistrationViewModel.Factory do — and they run on the main thread during
                // a navigation transition, long after [startupComplete] has opened the gate. Left
                // unforced, that first construction (Keystore unwrap, plus migratePlaintext's
                // synchronous commit()s on the migration path) lands on main mid-transition, which
                // is the exact stall this class exists to prevent and which the gate cannot help
                // with. Constructing it here keeps the gate's guarantee true for every factory.
                container.privacySettingsStore
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Constructing the container's stores does disk I/O and JSON decoding, either of
                // which can throw on a corrupt file. Uncaught, that reaches the thread's default
                // uncaught handler and kills the process, because applicationScope is a
                // SupervisorJob with no CoroutineExceptionHandler -- and it would do so while
                // every awaitStarted() caller raced the crash instead of being released cleanly by
                // the invokeOnCompletion paths the rest of this wiring is built around.
                //
                // Degrading to "automatic clock in/out is not running" beats taking the app down:
                // manual clock in/out is driven from the UI layer and still works.
                Log.e(TAG, "Startup wiring failed; automatic clock in/out is disabled", e)
            }
        }
        // Release the UI gate on ANY terminal state, mirroring how awaitSubscribed's waiters are
        // released: a cancelled or failed startup must not leave the app on a loading screen.
        startupJob.invokeOnCompletion { _startupComplete.value = true }
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

    private companion object {
        private const val TAG = "EmployeeApp"
    }
}
