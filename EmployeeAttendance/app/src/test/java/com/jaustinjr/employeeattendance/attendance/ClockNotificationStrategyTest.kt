package com.jaustinjr.employeeattendance.attendance

import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockNotificationStrategyTest {

    private val worksite = WorkLocation("site-a", "Office", null, 37.0, -122.0, 100f)

    private class RecordingAttendance : AttendanceRepository {
        val clockIns = mutableListOf<String>()
        val clockOuts = mutableListOf<String>()
        override val attendance: StateFlow<Map<String, LocationAttendance>> =
            MutableStateFlow(emptyMap())
        override fun recordClockIn(locationId: String, epochMillis: Long, source: ClockSource) {
            clockIns += locationId
        }
        override fun recordClockOut(locationId: String, epochMillis: Long, source: ClockSource) {
            clockOuts += locationId
        }
        override fun undoLast(locationId: String) = Unit
    }

    private class RecordingNotifier : ClockNotifications {
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

    @Test
    fun `silent records without notifying`() {
        val attendance = RecordingAttendance()
        val notifier = RecordingNotifier()
        val strategy = ClockNotificationStrategy.forPreference(
            ClockNotificationPreference.SILENT, attendance, notifier,
        )

        strategy.onArrived(worksite)
        strategy.onDeparted(worksite)

        assertEquals(listOf("site-a"), attendance.clockIns)
        assertEquals(listOf("site-a"), attendance.clockOuts)
        assertTrue(notifier.recorded.isEmpty())
        assertTrue(notifier.confirms.isEmpty())
    }

    @Test
    fun `notify-with-undo records and posts an undoable notification`() {
        val attendance = RecordingAttendance()
        val notifier = RecordingNotifier()
        val strategy = ClockNotificationStrategy.forPreference(
            ClockNotificationPreference.NOTIFY_UNDO, attendance, notifier,
        )

        strategy.onArrived(worksite)
        strategy.onDeparted(worksite)

        assertEquals(listOf("site-a"), attendance.clockIns)
        assertEquals(listOf("site-a"), attendance.clockOuts)
        assertEquals(
            listOf(
                RecordingNotifier.Recorded("site-a", ClockType.CLOCK_IN, withUndo = true),
                RecordingNotifier.Recorded("site-a", ClockType.CLOCK_OUT, withUndo = true),
            ),
            notifier.recorded,
        )
    }

    @Test
    fun `confirm posts a prompt and records nothing yet`() {
        val attendance = RecordingAttendance()
        val notifier = RecordingNotifier()
        val strategy = ClockNotificationStrategy.forPreference(
            ClockNotificationPreference.CONFIRM, attendance, notifier,
        )

        strategy.onArrived(worksite)
        strategy.onDeparted(worksite)

        assertTrue(attendance.clockIns.isEmpty())
        assertTrue(attendance.clockOuts.isEmpty())
        assertEquals(
            listOf(
                RecordingNotifier.Confirm("site-a", ClockType.CLOCK_IN),
                RecordingNotifier.Confirm("site-a", ClockType.CLOCK_OUT),
            ),
            notifier.confirms,
        )
    }
}
