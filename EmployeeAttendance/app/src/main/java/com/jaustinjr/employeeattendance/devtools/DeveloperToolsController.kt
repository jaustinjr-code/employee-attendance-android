package com.jaustinjr.employeeattendance.devtools

import android.util.Log
import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.devtools.facade.DevAttendanceFacade
import com.jaustinjr.employeeattendance.devtools.facade.DevNotificationPreview
import com.jaustinjr.employeeattendance.devtools.facade.DevWorksiteFacade
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
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
 * ### Why it writes through facades to the real repositories
 * Every simulation here is applied through the app's own app-scoped sources of truth —
 * [ProximityRepository], [LocationStateRepository], and the attendance/worksite/notification
 * repositories behind the facades below — rather than through a parallel set of mock states. Feeding
 * a fake geofence transition into [ProximityRepository.onGeofenceTransition] is the *same* call the
 * real [com.jaustinjr.employeeattendance.location.geofence.GeofenceBroadcastReceiver] makes, so the
 * auto-clock engine, the notification strategy, persistence, and the UI all react exactly as they
 * would in the field. A simulation that took a shortcut around them would prove nothing.
 *
 * The consequence to keep in mind: these are real mutations. Simulating an arrival records real
 * attendance, and seeding a worksite registers a real geofence.
 *
 * ### Why three of the collaborators are facades
 * Acting on real data is the point; acting on it *indistinguishably from the user* is not. Where a
 * developer action could destroy or forge the user's own data, it goes through a narrow seam in
 * [com.jaustinjr.employeeattendance.devtools.facade] that (a) names the action so the developer intent
 * is visible at the call site, (b) tags what it writes as `ClockSource.SIMULATED`, and (c) simply
 * does not expose the dangerous operations — see [DevAttendanceFacade], [DevWorksiteFacade] and
 * [DevNotificationPreview] for what each one narrows.
 *
 * [ProximityRepository] and [LocationStateRepository] are used directly and deliberately: their
 * developer operations are transient state pokes with nothing persisted that a user would miss, so a
 * facade there would be ceremony without a risk to answer to.
 *
 * ### The limit of provenance tagging
 * Only this class's **direct** attendance writes ([forceClockIn]/[forceClockOut]) carry
 * `ClockSource.SIMULATED`. [simulateArrival] deliberately runs the production pipeline, which records
 * its own `AUTO` event; tagging that would mean threading a developer flag through production code
 * and would cost exactly the realism the simulation exists for. Pipeline-driven events are
 * indistinguishable from genuine ones by design, and [clearSimulatedData] will not remove them.
 *
 * Constructed only for debug builds — see [com.jaustinjr.employeeattendance.di.DefaultAppContainer].
 *
 * @param clock injectable time source, so time-dependent actions are assertable in tests.
 */
class DeveloperToolsController(
    private val settingsStore: DeveloperSettingsStore,
    private val permissionRepository: LocationPermissionRepository,
    private val proximityRepository: ProximityRepository,
    private val locationStateRepository: LocationStateRepository,
    private val worksites: DevWorksiteFacade,
    private val attendance: DevAttendanceFacade,
    private val notificationPreview: DevNotificationPreview,
    private val logExporter: DeveloperLogExporter,
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
        val active = worksites.activeWorksite.value
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
        val active = worksites.activeWorksite.value
            ?: return DevActionOutcome.NoActiveWorksite
        return publishFixNearActive(offsetMeters = active.radiusMeters + AWAY_MARGIN_METERS.toDouble())
    }

    private fun publishFixNearActive(offsetMeters: Double): DevActionOutcome {
        val active = worksites.activeWorksite.value
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
        worksites.seedSampleWorksite(
            latitudeDegrees = fix?.latitudeDegrees ?: FALLBACK_LATITUDE,
            longitudeDegrees = fix?.longitudeDegrees ?: FALLBACK_LONGITUDE,
        )
        return DevActionOutcome.Done
    }

    /**
     * Removes every registered worksite — the "no worksite yet" branch of the attendance screen.
     * This is the deliberately wide one: it takes the user's own worksites with it, which is why the
     * screen marks it destructive. To remove only what the developer tools created, use
     * [clearSimulatedData].
     */
    fun clearWorksites(): DevActionOutcome {
        worksites.removeAllWorksites()
        return DevActionOutcome.Done
    }

    // ---------------------------------------------------------------- attendance

    /**
     * Records a clock-in directly, without posting a notification. Written through
     * [DevAttendanceFacade], so the event is tagged `ClockSource.SIMULATED` and can be removed on its
     * own later by [clearSimulatedData].
     */
    fun forceClockIn(): DevActionOutcome = recordAttendance(ClockType.CLOCK_IN)

    /** Records a simulated clock-out directly; the counterpart of [forceClockIn]. */
    fun forceClockOut(): DevActionOutcome = recordAttendance(ClockType.CLOCK_OUT)

    private fun recordAttendance(type: ClockType): DevActionOutcome {
        // Mirrors LocationViewModel's manual-clock fallback: with no worksite the app still works as
        // a plain timeclock, and the developer screen should be able to drive that state too.
        val id = worksites.activeWorksite.value?.id
            ?: AttendanceRepository.GENERAL_TIMECLOCK_ID
        when (type) {
            ClockType.CLOCK_IN -> attendance.recordSimulatedClockIn(id, clock())
            ClockType.CLOCK_OUT -> attendance.recordSimulatedClockOut(id, clock())
        }
        return DevActionOutcome.Done
    }

    /**
     * Deletes the *whole* attendance log, returning the clock card to its never-clocked-in
     * appearance. The user's genuine history goes with it — [clearSimulatedData] is the surgical
     * alternative.
     */
    fun clearAttendance(): DevActionOutcome {
        attendance.clearAllAttendance()
        return DevActionOutcome.Done
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Previews one of the auto-clock notifications, so their layout and actions can be checked
     * without arranging a real transition. [withUndo] selects the undo variant of the "recorded"
     * card; [confirm] posts the confirm-first prompt instead.
     *
     * The card is the genuine one — same builder, same action buttons, the real worksite name — but
     * [DevNotificationPreview] posts it against a sandbox worksite id, so tapping **Undo** cannot
     * delete a real attendance event and **Confirm** cannot create one. See that interface for why
     * that was a live hazard.
     */
    fun postClockNotification(
        clockType: ClockType,
        withUndo: Boolean,
        confirm: Boolean,
    ): DevActionOutcome {
        val active = worksites.activeWorksite.value
            ?: return DevActionOutcome.NoActiveWorksite
        if (confirm) {
            notificationPreview.previewConfirmNotification(active, clockType)
        } else {
            notificationPreview.previewRecordedNotification(active, clockType, withUndo)
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
        val active = worksites.activeWorksite.value
        return buildString {
            appendLine("Employee Attendance — application log")
            appendLine("build: $buildDescription")
            appendLine("captured: ${clock()}")
            appendLine("permission: ${permission.accessLevel} precise=${permission.isPrecise}")
            appendLine("permission override: ${settingsStore.permissionOverride.value}")
            appendLine("tracking: ${locationStateRepository.trackingStatus.value}")
            appendLine("proximity: ${proximityRepository.proximity.value}")
            appendLine("worksites: ${worksites.registeredCount}, active=${active?.id}")
            appendLine("last fix: ${locationStateRepository.latestLocation.value?.timestampEpochMillis}")
            append("clocked in: ${active?.let { attendance.isClockedIn(it.id) } ?: false}")
        }
    }

    // ---------------------------------------------------------------- reset

    /**
     * Undoes the developer configuration: clears the persisted overrides *and* the simulated runtime
     * state they were used to produce, so the app is back to reporting reality.
     *
     * It deliberately leaves user data — worksites and attendance history — alone; those have their
     * own explicit actions ([clearSimulatedData], [clearWorksites], [clearAttendance]) because
     * destroying them is a different decision from turning the developer switches off.
     */
    fun resetDeveloperConfiguration(): DevActionOutcome {
        Log.d(TAG, "resetting developer configuration")
        settingsStore.reset()
        proximityRepository.reset()
        locationStateRepository.updateStatus(TrackingStatus.STOPPED)
        permissionRepository.refresh()
        return DevActionOutcome.Done
    }

    /**
     * Removes what the developer tools created and nothing else: attendance events tagged
     * `ClockSource.SIMULATED` and the dev sample worksite. The user's own worksites and their genuine
     * `AUTO`/`MANUAL` history survive.
     *
     * The caveat is the one in this class's KDoc: an event recorded by the *pipeline* during
     * [simulateArrival] is an ordinary `AUTO` event and is deliberately not removed here — there is
     * no way to tell it apart from a genuine one, which is the price of simulating through the real
     * path. Use [clearAttendance] if you need those gone too, knowing it takes everything.
     */
    fun clearSimulatedData(): DevActionOutcome {
        Log.d(TAG, "clearing developer-created data only")
        attendance.clearSimulatedAttendance()
        worksites.removeSampleWorksite()
        return DevActionOutcome.Done
    }

    private companion object {
        const val TAG = "DevTools"

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
