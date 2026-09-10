package com.jaustinjr.employeeattendance.devtools.facade

import android.util.Log
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockSource

/**
 * The only attendance capability the developer tools are given.
 *
 * ### What this narrows
 * [AttendanceRepository] is the user's attendance log. Handing the developer screen the repository
 * itself made a developer action spelled exactly like a user action — `recordClockIn(id)` at the call
 * site, an `AUTO` event in the persisted data — and exposed
 * [AttendanceRepository.undoLast], which silently deletes the most recent *real* event for a
 * worksite. This facade deliberately exposes **no undo at all**: a developer who wants an event gone
 * removes what the developer tools created ([clearSimulatedAttendance]) or, knowingly, everything
 * ([clearAllAttendance]).
 *
 * Reads are narrowed too — the controller only ever needed one boolean, so the derived-status map
 * does not cross this seam ([isClockedIn]).
 *
 * ### Provenance, and its limit
 * Every write made *through this facade* is tagged [ClockSource.SIMULATED], so simulated events are
 * distinguishable from genuine ones in the persisted log and can be removed on their own.
 *
 * That covers the controller's **direct** writes only. `DeveloperToolsController.simulateArrival()`
 * deliberately drives the real `ProximityRepository` ->
 * [com.jaustinjr.employeeattendance.attendance.AttendanceAutoClockController] ->
 * [com.jaustinjr.employeeattendance.attendance.ClockNotificationStrategy] pipeline, which calls
 * `recordClockIn` itself with the production source. Threading a "this is a simulation" flag through
 * that path would make the production pipeline aware of the developer tools and cost the realism the
 * feature exists for, so **pipeline-driven events stay indistinguishable by design** and
 * [clearSimulatedAttendance] will not remove them.
 */
interface DevAttendanceFacade {

    /** Records a clock-in tagged as developer-simulated. There is no user-facing equivalent name. */
    fun recordSimulatedClockIn(locationId: String, epochMillis: Long)

    /** Records a clock-out tagged as developer-simulated; the counterpart of [recordSimulatedClockIn]. */
    fun recordSimulatedClockOut(locationId: String, epochMillis: Long)

    /**
     * Deletes only the events this facade wrote ([ClockSource.SIMULATED]), leaving genuine `AUTO` and
     * `MANUAL` history intact. See the class KDoc for what "only" cannot cover.
     */
    fun clearSimulatedAttendance()

    /** Deletes the whole attendance log, genuine history included. The screen marks it destructive. */
    fun clearAllAttendance()

    /** Whether [locationId] is currently clocked in — the one derived read the developer tools need. */
    fun isClockedIn(locationId: String): Boolean
}

/** [DevAttendanceFacade] over the app's real [AttendanceRepository]. */
class RepositoryDevAttendanceFacade(
    private val repository: AttendanceRepository,
) : DevAttendanceFacade {

    override fun recordSimulatedClockIn(locationId: String, epochMillis: Long) {
        Log.d(TAG, "simulated CLOCK_IN for $locationId")
        repository.recordClockIn(locationId, epochMillis, ClockSource.SIMULATED)
    }

    override fun recordSimulatedClockOut(locationId: String, epochMillis: Long) {
        Log.d(TAG, "simulated CLOCK_OUT for $locationId")
        repository.recordClockOut(locationId, epochMillis, ClockSource.SIMULATED)
    }

    override fun clearSimulatedAttendance() {
        Log.d(TAG, "clearing simulated attendance only")
        repository.clearBySource(ClockSource.SIMULATED)
    }

    override fun clearAllAttendance() {
        repository.clearAll()
    }

    override fun isClockedIn(locationId: String): Boolean =
        repository.attendance.value[locationId]?.isClockedIn == true

    private companion object {
        const val TAG = "DevAttendance"
    }
}
