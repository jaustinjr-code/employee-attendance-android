package com.jaustinjr.employeeattendance.di

import android.content.Context
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.SystemLocationPermissionRepository
import com.jaustinjr.employeeattendance.location.tracking.FusedLocationTracker
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationTracker
import com.jaustinjr.employeeattendance.location.tracking.LocationTrackingController

/**
 * Application-scoped dependency graph. The project does not use a DI framework, so dependencies are
 * wired manually here and exposed to the rest of the app through
 * [com.jaustinjr.employeeattendance.EmployeeAttendanceApplication]. Singletons are created lazily
 * so a screen that never touches location does not pay for its dependencies.
 */
interface AppContainer {
    val locationPermissionRepository: LocationPermissionRepository
    val locationTracker: LocationTracker
    val locationStateRepository: LocationStateRepository
    val locationTrackingController: LocationTrackingController
}

/** Default [AppContainer] wiring the real, platform-backed implementations. */
class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    override val locationPermissionRepository: LocationPermissionRepository by lazy {
        SystemLocationPermissionRepository(appContext)
    }

    override val locationTracker: LocationTracker by lazy {
        FusedLocationTracker(appContext)
    }

    // Shared, app-scoped so producers (service / foreground collector) and consumers (proximity,
    // UI) observe the same latest fix.
    override val locationStateRepository: LocationStateRepository by lazy {
        LocationStateRepository()
    }

    override val locationTrackingController: LocationTrackingController by lazy {
        LocationTrackingController(appContext, locationStateRepository)
    }
}
