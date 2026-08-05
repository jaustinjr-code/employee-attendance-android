package com.jaustinjr.employeeattendance.attendance

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The derived attendance status for one worksite: its most recent clock-in and clock-out, plus
 * whether that clock-out was a manual (button) action. Consumers apply display rules on top of this
 * (e.g. only show a clock-out that is more recent than the clock-in, or only a manual one).
 */
data class LocationAttendance(
    val lastClockInMillis: Long? = null,
    val lastClockOutMillis: Long? = null,
    val lastClockOutManual: Boolean = false,
) {
    /** Currently clocked in: there is a clock-in and no later clock-out. */
    val isClockedIn: Boolean
        get() {
            val inMillis = lastClockInMillis ?: return false
            val outMillis = lastClockOutMillis ?: return true
            return inMillis > outMillis
        }
}

/**
 * Records attendance (clock-in / clock-out) for worksites and exposes the derived per-location
 * status. Replaces the earlier in-memory `LocationClockInRepository` stub with a real, persistent
 * Local + Remote implementation ([DefaultAttendanceRepository]).
 */
interface AttendanceRepository {

    /** Derived attendance status per work-location id. */
    val attendance: StateFlow<Map<String, LocationAttendance>>

    /** Records a clock-in for [locationId]. [source] defaults to automatic (geofence-driven). */
    fun recordClockIn(
        locationId: String,
        epochMillis: Long = System.currentTimeMillis(),
        source: ClockSource = ClockSource.AUTO,
    )

    /** Records a clock-out for [locationId]. [source] defaults to automatic (geofence-driven). */
    fun recordClockOut(
        locationId: String,
        epochMillis: Long = System.currentTimeMillis(),
        source: ClockSource = ClockSource.AUTO,
    )

    /**
     * Records [type] for [locationId] **only if it actually changes** the clocked-in state — a
     * clock-in while clocked out, or a clock-out while clocked in — and reports whether it did.
     *
     * This is the entry point for the geofence-driven auto-clock path, where "the user crossed the
     * boundary" does not by itself mean "there is a session to open/close": the clock-in may have
     * been undone from its notification, or never made at all.
     *
     * The default body below is a plain check-then-act and is therefore **not** atomic; it exists so
     * simple implementations and test doubles work without extra effort. Any implementation with
     * concurrent producers — the background proximity thread, the notification receiver and the
     * manual button all record — should override it to hold the check and the append under one lock,
     * as [DefaultAttendanceRepository] does.
     *
     * @return true if an event was appended, false if it would have been redundant.
     */
    fun recordIfStateChanges(
        locationId: String,
        type: ClockType,
        epochMillis: Long = System.currentTimeMillis(),
        source: ClockSource = ClockSource.AUTO,
    ): Boolean {
        // Non-atomic fallback so simple implementations (and test doubles) need no extra work.
        if (isClockedIn(locationId) == (type == ClockType.CLOCK_IN)) return false
        when (type) {
            ClockType.CLOCK_IN -> recordClockIn(locationId, epochMillis, source)
            ClockType.CLOCK_OUT -> recordClockOut(locationId, epochMillis, source)
        }
        return true
    }

    /** Whether [locationId] currently has an open clock-in. */
    fun isClockedIn(locationId: String): Boolean =
        attendance.value[locationId]?.isClockedIn == true

    /**
     * Reverses the most recent event for [locationId], if any. Backs the "Undo" notification action
     * after an automatic clock-in/out.
     */
    fun undoLast(locationId: String)

    /** Deletes all recorded attendance. Backs "delete all data". */
    fun clearAll() {}

    companion object {
        /**
         * Location id used for manual clock in/out when no worksite is active, so the app works as a
         * plain timeclock for users who grant no location or register no worksites.
         */
        const val GENERAL_TIMECLOCK_ID = "__general_timeclock__"
    }
}

/**
 * Persistent [AttendanceRepository] over a Local + Remote data-source pair. The local event log is
 * the source of truth (seeded on construction, written back on every mutation); the remote source is
 * mirrored best-effort on [ioScope] and is a no-op until a backend exists.
 *
 * Mutations are `@Synchronized` so concurrent producers — the auto-clock controller (background
 * geofence thread), the notification-action receiver, and the manual clock button — can't interleave
 * and corrupt the log.
 */
class DefaultAttendanceRepository(
    private val local: AttendanceLocalDataSource,
    private val remote: AttendanceRemoteDataSource = StubAttendanceRemoteDataSource(),
    private val ioScope: CoroutineScope,
) : AttendanceRepository {

    private var events: List<AttendanceEvent> = local.load()

    /**
     * Derived state, republished **synchronously** inside every mutation. It deliberately does not
     * use `stateIn(ioScope)`: that would recompute on [ioScope]'s dispatcher, so a caller that
     * recorded a clock-in could still read `isClockedIn == false` microseconds later. The auto-clock
     * guards read this flow immediately after recording, so it has to be up to date on return.
     */
    private val _attendance = MutableStateFlow(attendanceByLocation(events))

    override val attendance: StateFlow<Map<String, LocationAttendance>> = _attendance.asStateFlow()

    override fun recordClockIn(locationId: String, epochMillis: Long, source: ClockSource) =
        append(AttendanceEvent(locationId, ClockType.CLOCK_IN, epochMillis, source))

    override fun recordClockOut(locationId: String, epochMillis: Long, source: ClockSource) =
        append(AttendanceEvent(locationId, ClockType.CLOCK_OUT, epochMillis, source))

    /**
     * Atomic check-and-append: holding the same monitor as [append] means the "is there a session to
     * open/close?" decision cannot be raced by a concurrent producer.
     */
    @Synchronized
    override fun recordIfStateChanges(
        locationId: String,
        type: ClockType,
        epochMillis: Long,
        source: ClockSource,
    ): Boolean = super<AttendanceRepository>.recordIfStateChanges(locationId, type, epochMillis, source)

    @Synchronized
    private fun append(event: AttendanceEvent) {
        Log.d(TAG, "record ${event.type}/${event.source} for ${event.locationId} at ${event.epochMillis}")
        publish(events + event)
        ioScope.launch {
            runCatching { remote.push(event) }
                .onFailure { Log.w(TAG, "remote push failed", it) }
        }
    }

    @Synchronized
    override fun undoLast(locationId: String) {
        val index = events.indexOfLast { it.locationId == locationId }
        if (index < 0) {
            Log.d(TAG, "undoLast: nothing to undo for $locationId")
            return
        }
        val removed = events[index]
        Log.d(TAG, "undoLast: removing ${removed.type} for $locationId at ${removed.epochMillis}")
        publish(events.toMutableList().apply { removeAt(index) })
        ioScope.launch {
            runCatching { remote.delete(removed) }
                .onFailure { Log.w(TAG, "remote delete failed", it) }
        }
    }

    @Synchronized
    override fun clearAll() {
        Log.d(TAG, "clearAll: deleting all attendance events")
        publish(emptyList())
    }

    /** Commits [updated] as the new log: persist it, then republish the derived state in step. */
    private fun publish(updated: List<AttendanceEvent>) {
        events = updated
        local.save(updated)
        _attendance.value = attendanceByLocation(updated)
    }

    private fun attendanceByLocation(log: List<AttendanceEvent>): Map<String, LocationAttendance> =
        log.groupBy { it.locationId }.mapValues { (_, locationEvents) ->
            val lastIn = locationEvents
                .filter { it.type == ClockType.CLOCK_IN }
                .maxByOrNull { it.epochMillis }
            val lastOut = locationEvents
                .filter { it.type == ClockType.CLOCK_OUT }
                .maxByOrNull { it.epochMillis }
            LocationAttendance(
                lastClockInMillis = lastIn?.epochMillis,
                lastClockOutMillis = lastOut?.epochMillis,
                lastClockOutManual = lastOut?.source == ClockSource.MANUAL,
            )
        }

    private companion object {
        private const val TAG = "AttendRepo"
    }
}
