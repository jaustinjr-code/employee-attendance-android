package com.jaustinjr.employeeattendance.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the notification action buttons do to the attendance log — the decision half of
 * [ClockActionReceiver], extracted so it can be exercised without a `Context`.
 */
class ClockActionHandlerTest {

    private fun undo(
        repository: AttendanceRepository,
        locationId: String,
        type: ClockType,
        epochMillis: Long,
    ) = ClockActionHandler.handle(
        repository = repository,
        action = ClockActionReceiver.ACTION_UNDO,
        locationId = locationId,
        clockType = type,
        epochMillis = epochMillis,
    )

    private fun confirm(
        repository: AttendanceRepository,
        locationId: String,
        type: ClockType,
    ) = ClockActionHandler.handle(
        repository = repository,
        action = ClockActionReceiver.ACTION_CONFIRM,
        locationId = locationId,
        clockType = type,
        epochMillis = ClockActionHandler.NO_EVENT,
    )

    /** Issue #23: the headline case. */
    @Test
    fun `undo from a stale clock-in card reverses the clock-in, not the newer clock-out`() {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn("site-a", 1_000L)
        attendance.recordClockOut("site-a", 2_000L)

        // The clock-in notification was never dismissed; its Undo names the 1_000 clock-in.
        assertTrue(undo(attendance, "site-a", ClockType.CLOCK_IN, 1_000L))

        assertEquals(
            listOf(AttendanceEvent("site-a", ClockType.CLOCK_OUT, 2_000L)),
            attendance.events,
        )
    }

    @Test
    fun `undo of an already-reversed event is a no-op`() {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn("site-a", 1_000L)

        assertTrue(undo(attendance, "site-a", ClockType.CLOCK_IN, 1_000L))
        // Second tap (e.g. from a duplicate card) must not eat a different event.
        assertFalse(undo(attendance, "site-a", ClockType.CLOCK_IN, 1_000L))

        assertTrue(attendance.events.isEmpty())
    }

    @Test
    fun `undo that names no event changes nothing`() {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn("site-a", 1_000L)
        attendance.recordClockOut("site-a", 2_000L)

        // A pending intent built before the timestamp extra existed.
        assertFalse(undo(attendance, "site-a", ClockType.CLOCK_IN, ClockActionHandler.NO_EVENT))

        assertEquals(2, attendance.events.size)
    }

    @Test
    fun `undo does not cross worksites`() {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn("site-a", 1_000L)

        assertFalse(undo(attendance, "site-b", ClockType.CLOCK_IN, 1_000L))

        assertEquals(1, attendance.events.size)
    }

    @Test
    fun `confirm records the prompted event`() {
        val attendance = RecordingAttendanceRepository()

        assertTrue(confirm(attendance, "site-a", ClockType.CLOCK_IN))

        assertTrue(attendance.isClockedIn("site-a"))
    }

    @Test
    fun `confirming a clock-out with no open session records nothing`() {
        val attendance = RecordingAttendanceRepository()

        assertFalse(confirm(attendance, "site-a", ClockType.CLOCK_OUT))

        assertTrue(attendance.events.isEmpty())
    }

    @Test
    fun `confirming the same clock-in twice records it once`() {
        val attendance = RecordingAttendanceRepository()

        assertTrue(confirm(attendance, "site-a", ClockType.CLOCK_IN))
        assertFalse(confirm(attendance, "site-a", ClockType.CLOCK_IN))

        assertEquals(1, attendance.events.count { it.type == ClockType.CLOCK_IN })
    }

    @Test
    fun `an unknown action is ignored`() {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn("site-a", 1_000L)

        val handled = ClockActionHandler.handle(
            repository = attendance,
            action = "com.example.SOMETHING_ELSE",
            locationId = "site-a",
            clockType = ClockType.CLOCK_IN,
            epochMillis = 1_000L,
        )

        assertFalse(handled)
        assertEquals(1, attendance.events.size)
    }

    @Test
    fun `the undo target handed to the notifier is the event that was just recorded`() {
        // Ties the two halves together: what NotifyWithUndoStrategy tells the notifier to undo must
        // be exactly the event the repository appended, or the Undo button would name the wrong one.
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val worksite = com.jaustinjr.employeeattendance.location.registration.WorkLocation(
            "site-a", "Office", null, 37.0, -122.0, 100f,
        )
        NotifyWithUndoStrategy(attendance, notifier).onArrived(worksite)

        val target = notifier.undoTargets.single()
        assertEquals(attendance.events.single(), target)

        assertTrue(undo(attendance, target.locationId, target.type, target.epochMillis))
        assertTrue(attendance.events.isEmpty())
        assertNull(attendance.attendance.value["site-a"]?.lastClockInMillis)
    }
}
