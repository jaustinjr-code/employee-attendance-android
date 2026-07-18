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
 * Records attendance (clock-in / clock-out) for worksites and exposes the most recent clock-in per
 * location for display. Replaces the earlier in-memory `LocationClockInRepository` stub with a real,
 * persistent Local + Remote implementation ([DefaultAttendanceRepository]).
 */
interface AttendanceRepository {

    /**
     * Map of work-location id to the epoch-millis of its most recent clock-in. Preserves the API the
     * location detail UI already consumes.
     */
    val lastClockIns: StateFlow<Map<String, Long>>

    /** Records a clock-in for [locationId] at [epochMillis] (defaults to now). */
    fun recordClockIn(locationId: String, epochMillis: Long = System.currentTimeMillis())

    /** Records a clock-out for [locationId] at [epochMillis] (defaults to now). */
    fun recordClockOut(locationId: String, epochMillis: Long = System.currentTimeMillis())

    /**
     * Reverses the most recent event for [locationId], if any. Backs the "Undo" notification action
     * after an automatic clock-in/out.
     */
    fun undoLast(locationId: String)
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

    override val lastClockIns: StateFlow<Map<String, Long>> = _events
        .map { events -> latestClockInsByLocation(events) }
        .stateIn(
            scope = ioScope,
            started = SharingStarted.Eagerly,
            initialValue = latestClockInsByLocation(_events.value),
        )

    override fun recordClockIn(locationId: String, epochMillis: Long) =
        append(AttendanceEvent(locationId, ClockType.CLOCK_IN, epochMillis))

    override fun recordClockOut(locationId: String, epochMillis: Long) =
        append(AttendanceEvent(locationId, ClockType.CLOCK_OUT, epochMillis))

    @Synchronized
    private fun append(event: AttendanceEvent) {
        Log.d(TAG, "record ${event.type} for ${event.locationId} at ${event.epochMillis}")
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

    private fun latestClockInsByLocation(events: List<AttendanceEvent>): Map<String, Long> =
        events.asSequence()
            .filter { it.type == ClockType.CLOCK_IN }
            .groupingBy { it.locationId }
            .fold(0L) { max, event -> maxOf(max, event.epochMillis) }

    private companion object {
        private const val TAG = "AttendRepo"
    }
}
