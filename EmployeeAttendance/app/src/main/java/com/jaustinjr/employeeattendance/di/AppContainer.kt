package com.jaustinjr.employeeattendance.di

import android.content.Context
import com.jaustinjr.employeeattendance.BuildConfig
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.attendance.AttendanceAutoClockController
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockNotifier
import com.jaustinjr.employeeattendance.attendance.ClockOutListener
import com.jaustinjr.employeeattendance.attendance.ClockOutSource
import com.jaustinjr.employeeattendance.attendance.DefaultAttendanceRepository
import com.jaustinjr.employeeattendance.attendance.SharedPrefsAttendanceLocalDataSource
import com.jaustinjr.employeeattendance.devtools.DebugLocationPermissionRepository
import com.jaustinjr.employeeattendance.devtools.DeveloperLogExporter
import com.jaustinjr.employeeattendance.devtools.DeveloperSettingsStore
import com.jaustinjr.employeeattendance.devtools.DeveloperToolsController
import com.jaustinjr.employeeattendance.devtools.EmailDeveloperLogExporter
import com.jaustinjr.employeeattendance.devtools.SharedPrefsDeveloperSettingsStore
import com.jaustinjr.employeeattendance.devtools.LogcatApplicationLogSource
import com.jaustinjr.employeeattendance.devtools.facade.DevAttendanceFacade
import com.jaustinjr.employeeattendance.devtools.facade.DevNotificationPreview
import com.jaustinjr.employeeattendance.devtools.facade.DevWorksiteFacade
import com.jaustinjr.employeeattendance.devtools.facade.RepositoryDevAttendanceFacade
import com.jaustinjr.employeeattendance.devtools.facade.RepositoryDevWorksiteFacade
import com.jaustinjr.employeeattendance.devtools.facade.SandboxedDevNotificationPreview
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
import com.jaustinjr.employeeattendance.reporting.BiweeklyReportController
import com.jaustinjr.employeeattendance.reporting.BiweeklyReportNotifier
import com.jaustinjr.employeeattendance.reporting.BiweeklyReportRunner
import com.jaustinjr.employeeattendance.reporting.FileReportSharer
import com.jaustinjr.employeeattendance.reporting.ReportGenerator
import com.jaustinjr.employeeattendance.reporting.ReportSharer
import com.jaustinjr.employeeattendance.reporting.WorkManagerReportScheduler
import com.jaustinjr.employeeattendance.settings.PrivacySettingsStore
import com.jaustinjr.employeeattendance.settings.ReportSettings
import com.jaustinjr.employeeattendance.settings.ReportSettingsStore
import com.jaustinjr.employeeattendance.settings.StatusUpdateSettingsStore
import com.jaustinjr.employeeattendance.settings.UserProfileStore
import com.jaustinjr.employeeattendance.statusupdate.AppForegroundTracker
import com.jaustinjr.employeeattendance.statusupdate.DefaultStatusUpdateRepository
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateCoordinator
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateNotifications
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateNotifier
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateRepository
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

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
    val statusUpdateSettingsStore: StatusUpdateSettingsStore
    val statusUpdateRepository: StatusUpdateRepository
    val statusUpdateCoordinator: StatusUpdateCoordinator

    /**
     * The single [ClockOutListener] every clock-out/undo producer reports through — the auto-clock
     * pipeline, [com.jaustinjr.employeeattendance.attendance.ClockActionReceiver]'s Confirm/Undo
     * actions — so there is exactly one place that maps [ClockOutSource] onto
     * [StatusUpdateTrigger] and calls [statusUpdateCoordinator].
     */
    val clockOutListener: ClockOutListener

    /** Wall clock in the device zone. Reporting reads time only through this. */
    val clock: Clock

    /** Cached report computation, shared by the Reports screen and the biweekly worker. */
    val reportGenerator: ReportGenerator
    val reportSettings: ReportSettings
    val reportSharer: ReportSharer
    val biweeklyReportController: BiweeklyReportController
    val biweeklyReportRunner: BiweeklyReportRunner

    /**
     * Persisted developer overrides. Only ever read from the debug-gated developer settings screen
     * and from the permission decorator below; in a release build nothing touches it, so the lazy
     * binding never runs.
     */
    val developerSettingsStore: DeveloperSettingsStore

    /** Actions behind the developer settings screen. Debug-only, as above. */
    val developerToolsController: DeveloperToolsController

    /**
     * The narrowed seams the developer tools reach user data through. They exist so a developer
     * action is spelled — and persisted — differently from a user action; see
     * [com.jaustinjr.employeeattendance.devtools.facade.DevAttendanceFacade]. Debug-only, as above.
     */
    val devAttendanceFacade: DevAttendanceFacade
    val devWorksiteFacade: DevWorksiteFacade
    val devNotificationPreview: DevNotificationPreview
}

/** Default [AppContainer] wiring the real, platform-backed implementations. */
class DefaultAppContainer(
    context: Context,
    /**
     * Passed in rather than built here: it must be registered before any Activity's first
     * `onStart`, which [com.jaustinjr.employeeattendance.EmployeeAttendanceApplication.onCreate]
     * guarantees by constructing it eagerly, ahead of this container. See that class for why.
     */
    private val appForegroundTracker: AppForegroundTracker,
) : AppContainer {

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
            clockOutListener = clockOutListener,
        )
    }

    override val clock: Clock = Clock.systemDefaultZone()

    override val reportGenerator: ReportGenerator by lazy { ReportGenerator() }

    override val reportSettings: ReportSettings by lazy { ReportSettingsStore(appContext) }

    override val reportSharer: ReportSharer by lazy { FileReportSharer(appContext) }

    override val biweeklyReportController: BiweeklyReportController by lazy {
        BiweeklyReportController(
            settings = reportSettings,
            scheduler = WorkManagerReportScheduler(appContext),
            clock = clock,
        )
    }

    override val biweeklyReportRunner: BiweeklyReportRunner by lazy {
        BiweeklyReportRunner(
            settings = reportSettings,
            attendanceRepository = attendanceRepository,
            workLocationRepository = workLocationRepository,
            generator = reportGenerator,
            notifier = BiweeklyReportNotifier(appContext),
            clock = clock,
        )
    }

    override val statusUpdateSettingsStore: StatusUpdateSettingsStore by lazy {
        StatusUpdateSettingsStore(appContext)
    }

    override val statusUpdateRepository: StatusUpdateRepository by lazy {
        DefaultStatusUpdateRepository()
    }

    private val statusUpdateNotifier: StatusUpdateNotifications by lazy {
        StatusUpdateNotifier(appContext)
    }

    override val statusUpdateCoordinator: StatusUpdateCoordinator by lazy {
        StatusUpdateCoordinator(
            enabled = statusUpdateSettingsStore.enabled,
            foregroundTracker = appForegroundTracker,
            notifier = statusUpdateNotifier,
            repository = statusUpdateRepository,
            attendanceRepository = attendanceRepository,
        )
    }

    override val clockOutListener: ClockOutListener by lazy {
        object : ClockOutListener {
            override fun onClockOut(locationId: String, clockOutAtMillis: Long, source: ClockOutSource) {
                val trigger = when (source) {
                    ClockOutSource.AUTO -> StatusUpdateTrigger.AUTO
                    ClockOutSource.NOTIFICATION_CONFIRMED -> StatusUpdateTrigger.NOTIFICATION_CONFIRMED
                }
                statusUpdateCoordinator.onClockOut(locationId, clockOutAtMillis, trigger)
            }

            override fun onClockOutUndone(locationId: String, clockOutAtMillis: Long) {
                statusUpdateCoordinator.onClockOutUndone(locationId, clockOutAtMillis)
            }
        }
    }

    override val developerSettingsStore: DeveloperSettingsStore by lazy {
        SharedPrefsDeveloperSettingsStore(appContext)
    }

    private val developerLogExporter: DeveloperLogExporter by lazy {
        EmailDeveloperLogExporter(appContext, LogcatApplicationLogSource())
    }

    override val devAttendanceFacade: DevAttendanceFacade by lazy {
        RepositoryDevAttendanceFacade(attendanceRepository)
    }

    override val devWorksiteFacade: DevWorksiteFacade by lazy {
        RepositoryDevWorksiteFacade(
            repository = workLocationRepository,
            // Resolved here so the facade itself needs no Context.
            sampleWorksiteName = appContext.getString(R.string.dev_sample_worksite_name),
        )
    }

    override val devNotificationPreview: DevNotificationPreview by lazy {
        SandboxedDevNotificationPreview(clockNotifier)
    }

    override val developerToolsController: DeveloperToolsController by lazy {
        DeveloperToolsController(
            settingsStore = developerSettingsStore,
            permissionRepository = locationPermissionRepository,
            proximityRepository = proximityRepository,
            locationStateRepository = locationStateRepository,
            // The three facades replace direct access to the worksite/attendance/notification
            // collaborators: the controller can no longer reach those repositories at all.
            worksites = devWorksiteFacade,
            attendance = devAttendanceFacade,
            notificationPreview = devNotificationPreview,
            logExporter = developerLogExporter,
            buildDescription = "${BuildConfig.BUILD_TYPE} ${BuildConfig.VERSION_NAME} " +
                "(${BuildConfig.VERSION_CODE})",
        )
    }
}
