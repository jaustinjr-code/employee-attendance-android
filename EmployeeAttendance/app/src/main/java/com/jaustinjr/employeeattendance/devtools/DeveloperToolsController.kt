package com.jaustinjr.employeeattendance.devtools

import android.util.Log
import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockNotifications
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.RadiusOption
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.TrackingStatus

/** The outcome of a developer action, mapped to user-facing text by the UI layer. */
sealed interface DevActionOutcome {
    /** The action ran. */
    data object Done : DevActionOutcome

    /** The action needs an active worksite and there is none registered. */
    data object NoActiveWorksite : DevActionOutcome
}

/**
 * The behaviour behind the developer settings screen: it drives the app into states a developer
 * would otherwise have to travel, wait, or re-grant permissions to reach.
 *
 * ### Why it writes to the real repositories
 * Every simulation here is applied through the app's own app-scoped sources of truth —
 * [ProximityRepository], [LocationStateRepository], [AttendanceRepository],
 * [WorkLocationRepository] — rather than through a parallel set of mock states. Feeding a fake
 * geofence transition into [ProximityRepository.onGeofenceTransition] is the *same* call the real
 * [com.jaustinjr.employeeattendance.location.geofence.GeofenceBroadcastReceiver] makes, so the
 * auto-clock engine, the notification strategy, persistence, and the UI all react exactly as they
 * would in the field. A simulation that took a shortcut around them would prove nothing.
 *
 * The consequence to keep in mind: these are real mutations. Simulating an arrival records real
 * attendance, and seeding a worksite registers a real geofence.
 *
 * Constructed only for debug builds — see [com.jaustinjr.employeeattendance.di.DefaultAppContainer].
 *
 * @param sampleWorksiteName display name for the seeded worksite, resolved from resources by the
 *   container so this class stays free of `Context`.
 * @param clock injectable time source, so time-dependent actions are assertable in tests.
 */
class DeveloperToolsController(
    private val settingsStore: DeveloperSettingsStore,
    private val permissionRepository: LocationPermissionRepository,
    private val proximityRepository: ProximityRepository,
    private val locationStateRepository: LocationStateRepository,
    private val workLocationRepository: WorkLocationRepository,
    private val attendanceRepository: AttendanceRepository,
    private val notifier: ClockNotifications,
    private val logExporter: DeveloperLogExporter,
    private val sampleWorksiteName: String,
    private val buildDescription: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ---------------------------------------------------------------- permission

    /**
     * Pins the permission state the whole app observes. Refreshes the underlying repository too, so
     * clearing the override immediately re-reads the real grant instead of waiting for the next
     * lifecycle resume.
     */
    fun setPermissionOverride(override: PermissionOverride): DevActionOutcome {
        settingsStore.setPermissionOverride(override)
        permissionRepository.refresh()
        return DevActionOutcome.Done
    }

    // ---------------------------------------------------------------- proximity

    /**
     * Fakes a geofence ENTER for the active worksite, exercising the full auto clock-in path
     * (proximity state -> `Arrived` -> the current notification strategy -> attendance).
     */
    fun simulateArrival(): DevActionOutcome = simulateTransition(ProximityState.INSIDE)

    /** Fakes a geofence EXIT for the active worksite; the auto clock-out counterpart of [simulateArrival]. */
    fun simulateDeparture(): DevActionOutcome = simulateTransition(ProximityState.OUTSIDE)

    private fun simulateTransition(state: ProximityState): DevActionOutcome {
        val active = workLocationRepository.activeWorkLocation.value
            ?: return DevActionOutcome.NoActiveWorksite
        Log.d(TAG, "simulating $state for ${active.id}")
        proximityRepository.onGeofenceTransition(active.id, state)
        return DevActionOutcome.Done
    }

    /**
     * Clears proximity back to UNKNOWN. Note this is [ProximityRepository.reset]'s documented
     * behaviour: clearing an INSIDE state emits a real `Departed`, so an auto clock-out may follow.
     */
    fun clearProximity(): DevActionOutcome {
        proximityRepository.reset()
        return DevActionOutcome.Done
    }

    // ---------------------------------------------------------------- location fixes

    /**
     * Publishes a fix at the active worksite's centre. Unlike [simulateArrival] this goes through
     * the *real* distance math and hysteresis in the foreground pipeline, so it is the way to
     * exercise [com.jaustinjr.employeeattendance.location.proximity.ProximityCalculator] end to end.
     *
     * A subsequent real fix from the tracker will overwrite this one, so the simulated position is
     * only stable where the device is not producing fixes (an emulator with no mock provider, or
     * with tracking stopped).
     */
    fun simulateLocationAtWorksite(): DevActionOutcome = publishFixNearActive(offsetMeters = 0.0)

    /**
     * Publishes a fix well outside the active worksite's radius — far enough to clear the exit
     * hysteresis buffer, so the foreground evaluator really does flip to OUTSIDE.
     */
    fun simulateLocationAwayFromWorksite(): DevActionOutcome {
        val active = workLocationRepository.activeWorkLocation.value
            ?: return DevActionOutcome.NoActiveWorksite
        return publishFixNearActive(offsetMeters = active.radiusMeters + AWAY_MARGIN_METERS.toDouble())
    }

    private fun publishFixNearActive(offsetMeters: Double): DevActionOutcome {
        val active = workLocationRepository.activeWorkLocation.value
            ?: return DevActionOutcome.NoActiveWorksite
        val (latitude, longitude) = DevGeo.offsetNorth(
            latitudeDegrees = active.latitudeDegrees,
            longitudeDegrees = active.longitudeDegrees,
            meters = offsetMeters,
        )
        Log.d(TAG, "publishing simulated fix ${offsetMeters}m from ${active.id}")
        locationStateRepository.publishLocation(
            LocationSample(
                latitudeDegrees = latitude,
                longitudeDegrees = longitude,
                accuracyMeters = SIMULATED_ACCURACY_METERS,
                timestampEpochMillis = clock(),
            )
        )
        return DevActionOutcome.Done
    }

    /** Forces the tracking status the UI reports, without waiting on the service to reach it. */
    fun setTrackingStatus(status: TrackingStatus): DevActionOutcome {
        locationStateRepository.updateStatus(status)
        return DevActionOutcome.Done
    }

    // ---------------------------------------------------------------- worksites

    /**
     * Registers a worksite so the "set up" branch of the UI is reachable in one tap. It is centred
     * on the latest observed fix when there is one — so simulating an arrival actually corresponds
     * to where the device is — and otherwise on a fixed fallback coordinate.
     */
    fun seedSampleWorksite(): DevActionOutcome {
        val fix = locationStateRepository.latestLocation.value
        val worksite = WorkLocation(
            id = SAMPLE_WORKSITE_ID,
            name = sampleWorksiteName,
            address = null,
            latitudeDegrees = fix?.latitudeDegrees ?: FALLBACK_LATITUDE,
            longitudeDegrees = fix?.longitudeDegrees ?: FALLBACK_LONGITUDE,
            radiusMeters = RadiusOption.DEFAULT.meters,
        )
        Log.d(TAG, "seeding sample worksite at ${worksite.latitudeDegrees},${worksite.longitudeDegrees}")
        workLocationRepository.registerWorkLocation(worksite)
        workLocationRepository.setActiveWorkLocation(worksite.id)
        return DevActionOutcome.Done
    }

    /** Removes every registered worksite — the "no worksite yet" branch of the attendance screen. */
    fun clearWorksites(): DevActionOutcome {
        workLocationRepository.clearAll()
        return DevActionOutcome.Done
    }

    // ---------------------------------------------------------------- attendance

    /** Records an automatic clock-in, as if a geofence had fired, without posting a notification. */
    fun forceClockIn(): DevActionOutcome = recordAttendance(ClockType.CLOCK_IN)

    /** Records an automatic clock-out, as if a geofence had fired, without posting a notification. */
    fun forceClockOut(): DevActionOutcome = recordAttendance(ClockType.CLOCK_OUT)

    private fun recordAttendance(type: ClockType): DevActionOutcome {
        // Mirrors LocationViewModel's manual-clock fallback: with no worksite the app still works as
        // a plain timeclock, and the developer screen should be able to drive that state too.
        val id = workLocationRepository.activeWorkLocation.value?.id
            ?: AttendanceRepository.GENERAL_TIMECLOCK_ID
        when (type) {
            ClockType.CLOCK_IN -> attendanceRepository.recordClockIn(id, clock(), ClockSource.AUTO)
            ClockType.CLOCK_OUT -> attendanceRepository.recordClockOut(id, clock(), ClockSource.AUTO)
        }
        return DevActionOutcome.Done
    }

    /** Deletes the attendance log, returning the clock card to its never-clocked-in appearance. */
    fun clearAttendance(): DevActionOutcome {
        attendanceRepository.clearAll()
        return DevActionOutcome.Done
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Posts one of the auto-clock notifications directly, so their layout and actions can be checked
     * without arranging a real transition. [withUndo] selects the undo variant of the "recorded"
     * card; [confirm] posts the confirm-first prompt instead.
     */
    fun postClockNotification(
        clockType: ClockType,
        withUndo: Boolean,
        confirm: Boolean,
    ): DevActionOutcome {
        val active = workLocationRepository.activeWorkLocation.value
            ?: return DevActionOutcome.NoActiveWorksite
        if (confirm) {
            notifier.notifyConfirm(active, clockType)
        } else {
            // A synthetic event: this action posts a card to look at and records nothing, so there
            // is no real event to name. The Undo action carries this timestamp, and `undoEvent`
            // only reverses the location's latest event when the type and timestamp both match —
            // so an unrecorded one names nothing and is a no-op.
            val preview = AttendanceEvent(active.id, clockType, clock(), ClockSource.AUTO)
            notifier.notifyRecorded(active, preview, withUndo)
        }
        return DevActionOutcome.Done
    }

    // ---------------------------------------------------------------- log export

    /** Emails the application log, pre-addressed to the developer's configured recipient. */
    suspend fun exportLog(): LogExportResult =
        logExporter.exportToEmail(
            recipient = settingsStore.logRecipient.value,
            header = describeCurrentState(),
        )

    /**
     * A snapshot of build and runtime state, prepended to an exported log so a log read later (or by
     * someone else) is self-describing. Deliberately developer-facing and unlocalised.
     */
    fun describeCurrentState(): String {
        val permission = permissionRepository.permissionState.value
        val active = workLocationRepository.activeWorkLocation.value
        return buildString {
            appendLine("Employee Attendance — application log")
            appendLine("build: $buildDescription")
            appendLine("captured: ${clock()}")
            appendLine("permission: ${permission.accessLevel} precise=${permission.isPrecise}")
            appendLine("permission override: ${settingsStore.permissionOverride.value}")
            appendLine("tracking: ${locationStateRepository.trackingStatus.value}")
            appendLine("proximity: ${proximityRepository.proximity.value}")
            appendLine("worksites: ${workLocationRepository.workLocations.value.size}, active=${active?.id}")
            appendLine("last fix: ${locationStateRepository.latestLocation.value?.timestampEpochMillis}")
            append("clocked in: ${attendanceRepository.attendance.value[active?.id]?.isClockedIn ?: false}")
        }
    }

    // ---------------------------------------------------------------- reset

    /**
     * Undoes the developer configuration: clears the persisted overrides *and* the simulated runtime
     * state they were used to produce, so the app is back to reporting reality.
     *
     * It deliberately leaves user data — worksites and attendance history — alone; those have their
     * own explicit actions ([clearWorksites], [clearAttendance]) because destroying them is a
     * different decision from turning the developer switches off.
     */
    fun resetDeveloperConfiguration(): DevActionOutcome {
        Log.d(TAG, "resetting developer configuration")
        settingsStore.reset()
        proximityRepository.reset()
        locationStateRepository.updateStatus(TrackingStatus.STOPPED)
        permissionRepository.refresh()
        return DevActionOutcome.Done
    }

    private companion object {
        const val TAG = "DevTools"

        /** Stable id so re-seeding replaces the sample rather than piling up duplicates. */
        const val SAMPLE_WORKSITE_ID = "dev-sample-worksite"

        /** Clear of the 50 m exit hysteresis buffer in `ProximityRepository`, with room to spare. */
        const val AWAY_MARGIN_METERS = 500f

        /** Plausible good-GPS accuracy, so nothing downstream treats the fix as suspect. */
        const val SIMULATED_ACCURACY_METERS = 10f

        /** Null Island is unmistakably synthetic; used only when no fix has ever been seen. */
        const val FALLBACK_LATITUDE = 0.0
        const val FALLBACK_LONGITUDE = 0.0
    }
}

/**
 * The small amount of geodesy the developer tools need to place a simulated fix a given distance
 * from a worksite. Kept separate and pure because `android.location.Location`'s distance math
 * returns 0 for everything under the JVM test stubs, so this must be verifiable on its own.
 */
internal object DevGeo {

    /** Metres per degree of latitude; near enough anywhere for placing a test fix. */
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

    /**
     * Returns the coordinate [meters] due north of the given point, clamped so a point near a pole
     * stays a valid latitude. Offsetting along latitude keeps the conversion independent of
     * longitude convergence except at the poles, where we fall back to an eastward offset.
     */
    fun offsetNorth(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        meters: Double,
    ): Pair<Double, Double> {
        if (meters == 0.0) return latitudeDegrees to longitudeDegrees
        val deltaDegrees = meters / METERS_PER_DEGREE_LATITUDE
        val north = latitudeDegrees + deltaDegrees
        if (north <= MAX_LATITUDE) return north to longitudeDegrees

        // Too close to the north pole to move north and stay valid: go south by the same distance.
        val south = latitudeDegrees - deltaDegrees
        if (south >= -MAX_LATITUDE) return south to longitudeDegrees

        // Degenerate input (an offset larger than the planet); leave the point where it is.
        return latitudeDegrees to longitudeDegrees
    }

    private const val MAX_LATITUDE = 90.0
}
