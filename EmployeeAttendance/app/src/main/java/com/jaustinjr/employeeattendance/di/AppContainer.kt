package com.jaustinjr.employeeattendance.di

import android.content.Context
import com.jaustinjr.employeeattendance.attendance.AttendanceAutoClockController
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockNotifier
import com.jaustinjr.employeeattendance.attendance.DefaultAttendanceRepository
import com.jaustinjr.employeeattendance.attendance.SharedPrefsAttendanceLocalDataSource
import com.jaustinjr.employeeattendance.location.LocationFeatureCoordinator
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.SystemLocationPermissionRepository
import com.jaustinjr.employeeattendance.location.geofence.GeofenceManager
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.SharedPrefsProximityStateStore
import com.jaustinjr.employeeattendance.location.registration.AddressAutocomplete
import com.jaustinjr.employeeattendance.location.registration.AddressGeocoder
import com.jaustinjr.employeeattendance.location.registration.DefaultWorkLocationRepository
import com.jaustinjr.employeeattendance.location.registration.PlatformAddressGeocoder
import com.jaustinjr.employeeattendance.location.registration.SharedPrefsWorkLocationLocalDataSource
import com.jaustinjr.employeeattendance.location.registration.StubAddressAutocomplete
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.DefaultTrackingServiceLauncher
import com.jaustinjr.employeeattendance.location.tracking.FusedLocationTracker
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationTracker
import com.jaustinjr.employeeattendance.location.tracking.LocationTrackingController
import com.jaustinjr.employeeattendance.settings.ClockNotificationSettingsStore
import com.jaustinjr.employeeattendance.settings.PrivacySettingsStore
import com.jaustinjr.employeeattendance.startup.ForegroundGate
import com.jaustinjr.employeeattendance.startup.ProcessLifecycleForegroundGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val proximityRepository: ProximityRepository
    val geofenceManager: GeofenceManager
    val workLocationRepository: WorkLocationRepository
    val addressGeocoder: AddressGeocoder
    val addressAutocomplete: AddressAutocomplete
    val attendanceRepository: AttendanceRepository
    val clockNotifier: ClockNotifier
    val clockNotificationSettingsStore: ClockNotificationSettingsStore
    val privacySettingsStore: PrivacySettingsStore
    val locationFeatureCoordinator: LocationFeatureCoordinator
    val attendanceAutoClockController: AttendanceAutoClockController
    val foregroundGate: ForegroundGate
}

/** Default [AppContainer] wiring the real, platform-backed implementations. */
class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    // App-lifetime scope for repository background work (persistence-derived flows, best-effort
    // remote mirroring). Default dispatcher: the work is light and non-blocking.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        LocationTrackingController(
            serviceLauncher = DefaultTrackingServiceLauncher(appContext),
            locationState = locationStateRepository,
        )
    }

    override val proximityRepository: ProximityRepository by lazy {
        ProximityRepository(SharedPrefsProximityStateStore(appContext))
    }

    override val geofenceManager: GeofenceManager by lazy {
        GeofenceManager(appContext)
    }

    override val workLocationRepository: WorkLocationRepository by lazy {
        DefaultWorkLocationRepository(
            local = SharedPrefsWorkLocationLocalDataSource(appContext),
            ioScope = appScope,
        )
    }

    override val addressGeocoder: AddressGeocoder by lazy {
        PlatformAddressGeocoder(appContext)
    }

    // TODO(#6): Swap the stub for a real location-biased places/autocomplete client.
    override val addressAutocomplete: AddressAutocomplete by lazy {
        StubAddressAutocomplete()
    }

    override val attendanceRepository: AttendanceRepository by lazy {
        DefaultAttendanceRepository(
            local = SharedPrefsAttendanceLocalDataSource(appContext),
            ioScope = appScope,
        )
    }

    override val clockNotifier: ClockNotifier by lazy {
        ClockNotifier(appContext)
    }

    override val clockNotificationSettingsStore: ClockNotificationSettingsStore by lazy {
        ClockNotificationSettingsStore(appContext)
    }

    override val privacySettingsStore: PrivacySettingsStore by lazy {
        PrivacySettingsStore(appContext)
    }

    override val locationFeatureCoordinator: LocationFeatureCoordinator by lazy {
        LocationFeatureCoordinator(
            permissionRepository = locationPermissionRepository,
            workLocationRepository = workLocationRepository,
            trackingController = locationTrackingController,
            geofenceRegistrar = geofenceManager,
            locationState = locationStateRepository,
            proximityUpdater = proximityRepository,
        )
    }

    override val attendanceAutoClockController: AttendanceAutoClockController by lazy {
        AttendanceAutoClockController(
            proximityEvents = proximityRepository.events,
            workLocationRepository = workLocationRepository,
            attendanceRepository = attendanceRepository,
            notifier = clockNotifier,
            preference = clockNotificationSettingsStore.preference,
        )
    }

    override val foregroundGate: ForegroundGate by lazy {
        ProcessLifecycleForegroundGate()
    }
}
