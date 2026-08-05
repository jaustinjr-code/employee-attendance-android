package com.jaustinjr.employeeattendance.attendance

import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockNotificationStrategyTest {

    private val worksite = WorkLocation("site-a", "Office", null, 37.0, -122.0, 100f)

    private fun strategy(
        preference: ClockNotificationPreference,
        attendance: AttendanceRepository,
        notifier: ClockNotifications,
    ) = ClockNotificationStrategy.forPreference(preference, attendance, notifier)

    @Test
    fun `silent records without notifying`() {
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val strategy = strategy(ClockNotificationPreference.SILENT, attendance, notifier)

        strategy.onArrived(worksite)
        strategy.onDeparted(worksite)

        assertEquals(listOf("site-a"), attendance.clockIns)
        assertEquals(listOf("site-a"), attendance.clockOuts)
        assertTrue(notifier.recorded.isEmpty())
        assertTrue(notifier.confirms.isEmpty())
    }

    @Test
    fun `notify-with-undo records and posts an undoable notification`() {
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val strategy = strategy(ClockNotificationPreference.NOTIFY_UNDO, attendance, notifier)

        strategy.onArrived(worksite)
        strategy.onDeparted(worksite)

        assertEquals(listOf("site-a"), attendance.clockIns)
        assertEquals(listOf("site-a"), attendance.clockOuts)
        assertEquals(
            listOf(
                RecordingClockNotifier.Recorded("site-a", ClockType.CLOCK_IN, withUndo = true),
                RecordingClockNotifier.Recorded("site-a", ClockType.CLOCK_OUT, withUndo = true),
            ),
            notifier.recorded,
        )
    }

    @Test
    fun `confirm posts a prompt and records nothing yet`() {
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val strategy = strategy(ClockNotificationPreference.CONFIRM, attendance, notifier)

        strategy.onArrived(worksite)

        assertTrue(attendance.clockIns.isEmpty())
        assertTrue(attendance.clockOuts.isEmpty())
        assertEquals(
            listOf(RecordingClockNotifier.Confirm("site-a", ClockType.CLOCK_IN)),
            notifier.confirms,
        )
    }

    // --- Issue #13: departure must be guarded on the actual clocked-in state ---

    @Test
    fun `notify-with-undo departure after an undone clock-in records and notifies nothing`() {
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val strategy = strategy(ClockNotificationPreference.NOTIFY_UNDO, attendance, notifier)

        strategy.onArrived(worksite)
        // The user taps "Undo" on the clock-in notification while still inside the radius.
        attendance.undoLast(worksite.id)
        notifier.recorded.clear()

        // ...and only later walks out of the radius.
        strategy.onDeparted(worksite)

        assertTrue("clock-out must not be recorded", attendance.clockOuts.isEmpty())
        assertTrue("no clock-out notification must be posted", notifier.recorded.isEmpty())
        assertFalse(attendance.attendance.value[worksite.id]?.isClockedIn == true)
    }

    @Test
    fun `silent departure without an open clock-in records nothing`() {
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val strategy = strategy(ClockNotificationPreference.SILENT, attendance, notifier)

        strategy.onDeparted(worksite)

        assertTrue(attendance.clockOuts.isEmpty())
    }

    @Test
    fun `confirm departure without an open clock-in posts no prompt`() {
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val strategy = strategy(ClockNotificationPreference.CONFIRM, attendance, notifier)

        // Arrival prompt is never confirmed, so nothing is recorded.
        strategy.onArrived(worksite)
        notifier.confirms.clear()

        strategy.onDeparted(worksite)

        assertTrue(notifier.confirms.isEmpty())
    }

    @Test
    fun `arriving while already clocked in does not double-record`() {
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val strategy = strategy(ClockNotificationPreference.NOTIFY_UNDO, attendance, notifier)

        strategy.onArrived(worksite)
        strategy.onArrived(worksite)

        assertEquals(listOf("site-a"), attendance.clockIns)
        assertEquals(1, notifier.recorded.size)
    }

    @Test
    fun `departure still clocks out a manually recorded clock-in`() {
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val strategy = strategy(ClockNotificationPreference.NOTIFY_UNDO, attendance, notifier)

        attendance.recordClockIn(worksite.id, epochMillis = 1_000L, source = ClockSource.MANUAL)
        strategy.onDeparted(worksite)

        assertEquals(listOf("site-a"), attendance.clockOuts)
        assertEquals(
            listOf(RecordingClockNotifier.Recorded("site-a", ClockType.CLOCK_OUT, withUndo = true)),
            notifier.recorded,
        )
    }
}
