package com.jaustinjr.employeeattendance.attendance

import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** In-memory [AttendanceLocalDataSource] used by the attendance unit tests. */
class FakeAttendanceLocalDataSource(
    var stored: List<AttendanceEvent> = emptyList(),
) : AttendanceLocalDataSource {
    var saveCount = 0
    override fun load(): List<AttendanceEvent> = stored
    override fun save(events: List<AttendanceEvent>) {
        saveCount++
        stored = events
    }
}

/**
 * [AttendanceRepository] test double that keeps *real* derived state by delegating to
 * [DefaultAttendanceRepository] over an in-memory store, while recording the calls made to it.
 *
 * Delegating (rather than stubbing `attendance` with a fixed map) is what lets tests exercise the
 * clocked-in guards: `isClockedIn` reflects the events actually recorded and undone.
 */
class RecordingAttendanceRepository(
    private val local: FakeAttendanceLocalDataSource = FakeAttendanceLocalDataSource(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) : AttendanceRepository {

    private val delegate = DefaultAttendanceRepository(local = local, ioScope = scope)

    /** Location ids passed to [recordClockIn], in call order. */
    val clockIns = mutableListOf<String>()

    /** Location ids passed to [recordClockOut], in call order. */
    val clockOuts = mutableListOf<String>()

    /** The full persisted event log, oldest first. */
    val events: List<AttendanceEvent> get() = local.stored

    override val attendance: StateFlow<Map<String, LocationAttendance>> get() = delegate.attendance

    override fun recordClockIn(locationId: String, epochMillis: Long, source: ClockSource) {
        clockIns += locationId
        delegate.recordClockIn(locationId, epochMillis, source)
    }

    override fun recordClockOut(locationId: String, epochMillis: Long, source: ClockSource) {
        clockOuts += locationId
        delegate.recordClockOut(locationId, epochMillis, source)
    }

    override fun undoLast(locationId: String) = delegate.undoLast(locationId)

    override fun clearAll() = delegate.clearAll()
}

/** [ClockNotifications] test double capturing every posted notification. */
class RecordingClockNotifier : ClockNotifications {
    data class Recorded(val id: String, val type: ClockType, val withUndo: Boolean)
    data class Confirm(val id: String, val type: ClockType)

    val recorded = mutableListOf<Recorded>()
    val confirms = mutableListOf<Confirm>()

    override fun notifyRecorded(worksite: WorkLocation, clockType: ClockType, withUndo: Boolean) {
        recorded += Recorded(worksite.id, clockType, withUndo)
    }

    override fun notifyConfirm(worksite: WorkLocation, clockType: ClockType) {
        confirms += Confirm(worksite.id, clockType)
    }
}
