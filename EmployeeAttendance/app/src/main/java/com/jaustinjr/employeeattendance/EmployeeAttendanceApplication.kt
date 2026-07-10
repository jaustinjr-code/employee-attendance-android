package com.jaustinjr.employeeattendance

import android.app.Application
import com.jaustinjr.employeeattendance.di.AppContainer
import com.jaustinjr.employeeattendance.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application entry point that owns the app-scoped [AppContainer]. Screens and ViewModels reach
 * their dependencies through this container rather than constructing them ad hoc.
 */
class EmployeeAttendanceApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** App-lifetime scope for coordination that must run regardless of any screen being visible. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // Start the location feature's reactive coordination for the life of the process.
        container.locationFeatureCoordinator.start(applicationScope)
    }
}
