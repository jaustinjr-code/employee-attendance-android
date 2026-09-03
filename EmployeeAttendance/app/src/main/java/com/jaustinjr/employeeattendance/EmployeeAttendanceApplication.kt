package com.jaustinjr.employeeattendance

import android.app.Application
import com.jaustinjr.employeeattendance.di.AppContainer
import com.jaustinjr.employeeattendance.di.DefaultAppContainer
import com.jaustinjr.employeeattendance.startup.AppStartup
import com.jaustinjr.employeeattendance.startup.StartupTask
import com.jaustinjr.employeeattendance.startup.appLifetimeScope

/**
 * Application entry point that owns the app-scoped [AppContainer]. Screens and ViewModels reach
 * their dependencies through this container rather than constructing them ad hoc.
 */
class EmployeeAttendanceApplication : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * App-lifetime scope for coordination that must run regardless of any screen being visible.
     * Carries a `CoroutineExceptionHandler`; see [appLifetimeScope].
     */
    private val applicationScope = appLifetimeScope()

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        AppStartup(
            foregroundGate = container.foregroundGate,
            // Consuming proximity Arrived/Departed events to drive hands-off clock in/out starts no
            // service, and has to be listening when a geofence broadcast wakes the process with no
            // screen visible — so it runs at process creation.
            processCreateTasks = listOf(StartupTask(container.attendanceAutoClockController::start)),
            // Location coordination reconciles the LocationTrackingService, i.e. it calls
            // startForegroundService(). That is refused while the process is in the background, so
            // it waits until an Activity is STARTED. See issue #49.
            foregroundTasks = listOf(StartupTask(container.locationFeatureCoordinator::start)),
        ).run(applicationScope)
    }
}
