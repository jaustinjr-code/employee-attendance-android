package com.jaustinjr.employeeattendance.statusupdate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app currently has any [Activity] visible to the user. The seam behind "is the app
 * in the foreground right now?", so [StatusUpdateCoordinator] can decide between the in-app
 * "Start status update?" pop-up and a notification without depending on
 * [Application.ActivityLifecycleCallbacks] directly — and so it can be faked in JVM tests.
 */
interface AppForegroundTracker {
    val isForeground: StateFlow<Boolean>
}

/**
 * Counts started-but-not-yet-stopped activities via
 * [Application.registerActivityLifecycleCallbacks]. This app is single-Activity, so in practice
 * the count is 0 or 1, but a plain counter (rather than a boolean flipped in `onActivityStarted`/
 * `onActivityStopped`) is the standard-safe shape for this seam and costs nothing extra.
 *
 * Built and registered once, eagerly, in
 * [com.jaustinjr.employeeattendance.EmployeeAttendanceApplication.onCreate] — before any Activity's
 * first `onStart` — then passed into [com.jaustinjr.employeeattendance.di.DefaultAppContainer] as a
 * constructor parameter rather than built there; see that class for why. The callback object holds
 * no [Activity] or other short-lived reference, so it does not leak.
 */
class DefaultAppForegroundTracker(application: Application) : AppForegroundTracker {

    private val startedActivityCount = AtomicInteger(0)

    private val _isForeground = MutableStateFlow(false)
    override val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (startedActivityCount.incrementAndGet() == 1) {
                        _isForeground.value = true
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    // Clamped at 0: this must be registered before any Activity's first onStart
                    // (see AppContainer/EmployeeAttendanceApplication), but an unmatched onStop
                    // reaching this callback — from a stray platform edge case, or a future
                    // ordering regression — must not drive the count permanently negative, which
                    // would leave isForeground stuck false even while an Activity is genuinely
                    // resumed.
                    val count = startedActivityCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                    if (count == 0) {
                        _isForeground.value = false
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
