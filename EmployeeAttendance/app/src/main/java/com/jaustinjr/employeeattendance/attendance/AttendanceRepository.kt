package com.jaustinjr.employeeattendance.attendance

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    private val _events = MutableStateFlow(local.load())

    override val attendance: StateFlow<Map<String, LocationAttendance>> = _events
        .map { events -> attendanceByLocation(events) }
        .stateIn(
            scope = ioScope,
            started = SharingStarted.Eagerly,
            initialValue = attendanceByLocation(_events.value),
        )

    override fun recordClockIn(locationId: String, epochMillis: Long, source: ClockSource) =
        append(AttendanceEvent(locationId, ClockType.CLOCK_IN, epochMillis, source))

    override fun recordClockOut(locationId: String, epochMillis: Long, source: ClockSource) =
        append(AttendanceEvent(locationId, ClockType.CLOCK_OUT, epochMillis, source))

    @Synchronized
    private fun append(event: AttendanceEvent) {
        Log.d(TAG, "record ${event.type}/${event.source} for ${event.locationId} at ${event.epochMillis}")
        _events.value = _events.value + event
        local.save(_events.value)
        ioScope.launch {
            runCatching { remote.push(event) }
                .onFailure { Log.w(TAG, "remote push failed", it) }
        }
    }

    @Synchronized
    override fun undoLast(locationId: String) {
        val index = _events.value.indexOfLast { it.locationId == locationId }
        if (index < 0) {
            Log.d(TAG, "undoLast: nothing to undo for $locationId")
            return
        }
        val removed = _events.value[index]
        Log.d(TAG, "undoLast: removing ${removed.type} for $locationId at ${removed.epochMillis}")
        _events.value = _events.value.toMutableList().apply { removeAt(index) }
        local.save(_events.value)
        ioScope.launch {
            runCatching { remote.delete(removed) }
                .onFailure { Log.w(TAG, "remote delete failed", it) }
        }
    }

    @Synchronized
    override fun clearAll() {
        Log.d(TAG, "clearAll: deleting all attendance events")
        _events.value = emptyList()
        local.save(_events.value)
    }

    private fun attendanceByLocation(events: List<AttendanceEvent>): Map<String, LocationAttendance> =
        events.groupBy { it.locationId }.mapValues { (_, locationEvents) ->
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
