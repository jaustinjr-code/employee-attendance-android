package com.jaustinjr.employeeattendance.di

import android.content.Context
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.SystemLocationPermissionRepository

/**
 * Application-scoped dependency graph. The project does not use a DI framework, so dependencies are
 * wired manually here and exposed to the rest of the app through
 * [com.jaustinjr.employeeattendance.EmployeeAttendanceApplication]. Singletons are created lazily
 * so a screen that never touches location does not pay for its dependencies.
 */
interface AppContainer {
    val locationPermissionRepository: LocationPermissionRepository
}

/** Default [AppContainer] wiring the real, platform-backed implementations. */
class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    override val locationPermissionRepository: LocationPermissionRepository by lazy {
        SystemLocationPermissionRepository(appContext)
    }
}
