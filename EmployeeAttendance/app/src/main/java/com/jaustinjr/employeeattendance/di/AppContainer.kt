package com.jaustinjr.employeeattendance.di

import android.content.Context
import com.jaustinjr.employeeattendance.BuildConfig
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.attendance.AttendanceAutoClockController
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockNotifier
import com.jaustinjr.employeeattendance.attendance.DefaultAttendanceRepository
import com.jaustinjr.employeeattendance.attendance.SharedPrefsAttendanceLocalDataSource
import com.jaustinjr.employeeattendance.devtools.DebugLocationPermissionRepository
import com.jaustinjr.employeeattendance.devtools.DeveloperLogExporter
import com.jaustinjr.employeeattendance.devtools.DeveloperSettingsStore
import com.jaustinjr.employeeattendance.devtools.DeveloperToolsController
import com.jaustinjr.employeeattendance.devtools.EmailDeveloperLogExporter
import com.jaustinjr.employeeattendance.devtools.SharedPrefsDeveloperSettingsStore
import com.jaustinjr.employeeattendance.devtools.LogcatApplicationLogSource
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
import com.jaustinjr.employeeattendance.settings.UserProfileStore
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
    val userProfileStore: UserProfileStore
    val locationFeatureCoordinator: LocationFeatureCoordinator
    val attendanceAutoClockController: AttendanceAutoClockController

    /**
     * Persisted developer overrides. Only ever read from the debug-gated developer settings screen
     * and from the permission decorator below; in a release build nothing touches it, so the lazy
     * binding never runs.
     */
    val developerSettingsStore: DeveloperSettingsStore

    /** Actions behind the developer settings screen. Debug-only, as above. */
    val developerToolsController: DeveloperToolsController
}

/** Default [AppContainer] wiring the real, platform-backed implementations. */
class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    // App-lifetime scope for repository background work (persistence-derived flows, best-effort
    // remote mirroring). Default dispatcher: the work is light and non-blocking.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * In debug builds the system repository is wrapped so developer settings can pin the permission
     * state the *entire* app observes — the coordinator, the tracking service pre-check and both
     * ViewModels all read permission from this one binding, so an override exercises the real
     * reactions rather than only repainting the UI. Release builds bind the system repository
     * directly, so no override path exists at all.
     */
    override val locationPermissionRepository: LocationPermissionRepository by lazy {
        val system = SystemLocationPermissionRepository(appContext)
        if (BuildConfig.DEBUG) {
            DebugLocationPermissionRepository(
                delegate = system,
                override = developerSettingsStore.permissionOverride,
                scope = appScope,
            )
        } else {
            system
        }
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

    override val userProfileStore: UserProfileStore by lazy {
        UserProfileStore(appContext)
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

    override val developerSettingsStore: DeveloperSettingsStore by lazy {
        SharedPrefsDeveloperSettingsStore(appContext)
    }

    private val developerLogExporter: DeveloperLogExporter by lazy {
        EmailDeveloperLogExporter(appContext, LogcatApplicationLogSource())
    }

    override val developerToolsController: DeveloperToolsController by lazy {
        DeveloperToolsController(
            settingsStore = developerSettingsStore,
            permissionRepository = locationPermissionRepository,
            proximityRepository = proximityRepository,
            locationStateRepository = locationStateRepository,
            workLocationRepository = workLocationRepository,
            attendanceRepository = attendanceRepository,
            notifier = clockNotifier,
            logExporter = developerLogExporter,
            // Resolved here so the controller itself needs no Context.
            sampleWorksiteName = appContext.getString(R.string.dev_sample_worksite_name),
            buildDescription = "${BuildConfig.BUILD_TYPE} ${BuildConfig.VERSION_NAME} " +
                "(${BuildConfig.VERSION_CODE})",
        )
    }
}
