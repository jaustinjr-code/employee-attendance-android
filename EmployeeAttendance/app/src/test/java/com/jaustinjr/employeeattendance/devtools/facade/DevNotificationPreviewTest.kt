package com.jaustinjr.employeeattendance.devtools.facade

import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.devtools.FakeAttendanceRepository
import com.jaustinjr.employeeattendance.devtools.RecordingClockNotifications
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The preview sandbox. `ClockNotifier` wires a notification's Undo/Confirm buttons to
 * `ClockActionReceiver`, which resolves the *worksite id carried in the notification* against the
 * real attendance repository — so an unsandboxed preview's Undo really did delete a genuine event.
 *
 * These tests stand in for the receiver by doing exactly what it does with the id the notification
 * was posted under; `ClockActionReceiver` itself needs the framework and is out of reach on the JVM.
 */
class DevNotificationPreviewTest {

    private val office = WorkLocation(
        id = "office",
        name = "Downtown Office",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    private val realEvent = AttendanceEvent("office", ClockType.CLOCK_IN, 1_000L, ClockSource.MANUAL)

    private val notifications = RecordingClockNotifications()
    private val preview = SandboxedDevNotificationPreview(notifications)
    private val repository = FakeAttendanceRepository()

    /**
     * What `ClockActionReceiver` does for ACTION_UNDO: it names the exact event the card carries,
     * so this takes the posted notification's event rather than just a location id.
     */
    private fun tapUndo(event: AttendanceEvent) =
        repository.undoEvent(event.locationId, event.type, event.epochMillis)

    /** What `ClockActionReceiver` does for ACTION_CONFIRM on a clock-in. */
    private fun tapConfirm(locationId: String) = repository.recordClockIn(locationId)

    @Test
    fun `a preview posts under the sandbox id, not the real worksite id`() {
        preview.previewRecordedNotification(office, ClockType.CLOCK_IN, withUndo = true)

        val posted = notifications.recorded.single().worksite
        assertEquals(DevNotificationPreview.DEV_PREVIEW_WORKSITE_ID, posted.id)
        assertNotEquals(office.id, posted.id)
    }

    @Test
    fun `a preview still renders the genuine worksite name and geometry`() {
        preview.previewConfirmNotification(office, ClockType.CLOCK_OUT)

        val posted = notifications.confirms.single().worksite
        // The name is what the notification text interpolates, so the card being previewed is real.
        assertEquals(office.name, posted.name)
        assertEquals(office, posted.copy(id = office.id))
    }

    @Test
    fun `tapping Undo on a preview leaves a pre-existing real attendance record untouched`() {
        repository.recordClockIn(realEvent.locationId, realEvent.epochMillis, realEvent.source)
        preview.previewRecordedNotification(office, ClockType.CLOCK_IN, withUndo = true)

        val undone = tapUndo(notifications.recorded.single().event)

        // Defended twice over: the card names a synthetic event that was never recorded, and it
        // names it under the sandbox id rather than the real worksite's.
        assertFalse("a preview's undo must not name a real event", undone)
        assertEquals(listOf(realEvent), repository.events.toList())
    }

    @Test
    fun `tapping Confirm on a preview writes into a bucket nothing reads`() {
        repository.recordClockIn(realEvent.locationId, realEvent.epochMillis, realEvent.source)
        preview.previewConfirmNotification(office, ClockType.CLOCK_IN)

        tapConfirm(notifications.confirms.single().worksite.id)

        // The real worksite's log is unchanged; the confirm landed under the sandbox id.
        assertEquals(listOf(realEvent), repository.events.filter { it.locationId == office.id })
        assertEquals(
            1,
            repository.events.count {
                it.locationId == DevNotificationPreview.DEV_PREVIEW_WORKSITE_ID
            },
        )
    }

    @Test
    fun `the sandbox id is not a real worksite id or the general timeclock`() {
        assertNotEquals(
            AttendanceRepository.GENERAL_TIMECLOCK_ID,
            DevNotificationPreview.DEV_PREVIEW_WORKSITE_ID,
        )
        assertNotEquals(
            DevWorksiteFacade.SAMPLE_WORKSITE_ID,
            DevNotificationPreview.DEV_PREVIEW_WORKSITE_ID,
        )
    }

    @Test
    fun `a preview gets its own notification id, so it cannot replace a real card`() {
        // ClockNotifier derives the id from locationId.hashCode(); a different id means a different
        // card, so a preview can neither replace nor be replaced by a genuine notification.
        assertNotEquals(
            office.id.hashCode(),
            DevNotificationPreview.DEV_PREVIEW_WORKSITE_ID.hashCode(),
        )
    }
}
