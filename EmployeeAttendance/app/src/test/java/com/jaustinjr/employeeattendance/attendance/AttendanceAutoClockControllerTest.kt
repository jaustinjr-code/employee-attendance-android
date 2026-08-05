package com.jaustinjr.employeeattendance.attendance

import com.jaustinjr.employeeattendance.location.proximity.ProximityEvent
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceAutoClockControllerTest {

    private val worksite = WorkLocation("site-a", "Office", null, 37.0, -122.0, 100f)

    private class SingleWorkLocationRepository(private val site: WorkLocation) : WorkLocationRepository {
        override val workLocations: StateFlow<List<WorkLocation>> = MutableStateFlow(listOf(site))
        override val activeWorkLocation: StateFlow<WorkLocation?> = MutableStateFlow(site)
        override fun setActiveWorkLocation(id: String) = Unit
        override fun registerWorkLocation(location: WorkLocation) = Unit
        override fun removeWorkLocation(id: String) = Unit
    }

    private fun controller(
        events: MutableSharedFlow<ProximityEvent>,
        attendance: AttendanceRepository,
        notifier: ClockNotifications,
        preference: StateFlow<ClockNotificationPreference>,
    ) = AttendanceAutoClockController(
        proximityEvents = events,
        workLocationRepository = SingleWorkLocationRepository(worksite),
        attendanceRepository = attendance,
        notifier = notifier,
        preference = preference,
    )

    @Test
    fun `arrived and departed drive clock-in and clock-out under the notify-undo preference`() = runTest {
        val events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        controller(
            events, attendance, notifier,
            MutableStateFlow(ClockNotificationPreference.NOTIFY_UNDO),
        ).start(backgroundScope)
        runCurrent()

        events.emit(ProximityEvent.Arrived("site-a"))
        events.emit(ProximityEvent.Departed("site-a"))
        runCurrent()

        assertEquals(listOf("site-a"), attendance.clockIns)
        assertEquals(listOf("site-a"), attendance.clockOuts)
        assertEquals(2, notifier.recorded.size)
    }

    @Test
    fun `changing preference switches strategy on the next event`() = runTest {
        val events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        val preference = MutableStateFlow(ClockNotificationPreference.CONFIRM)
        controller(events, attendance, notifier, preference).start(backgroundScope)
        runCurrent()

        // Confirm mode: prompt, record nothing.
        events.emit(ProximityEvent.Arrived("site-a"))
        runCurrent()
        assertTrue(attendance.clockIns.isEmpty())
        assertEquals(1, notifier.confirms.size)

        // Switch to silent: next arrival records with no notification.
        preference.value = ClockNotificationPreference.SILENT
        events.emit(ProximityEvent.Arrived("site-a"))
        runCurrent()
        assertEquals(listOf("site-a"), attendance.clockIns)
    }

    @Test
    fun `events for an unknown target are ignored`() = runTest {
        val events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        controller(
            events, attendance, notifier,
            MutableStateFlow(ClockNotificationPreference.SILENT),
        ).start(backgroundScope)
        runCurrent()

        events.emit(ProximityEvent.Arrived("some-other-site"))
        runCurrent()

        assertTrue(attendance.clockIns.isEmpty())
    }

    /** Issue #13: an undone auto clock-in must not be followed by a clock-out on the way out. */
    @Test
    fun `departure after the clock-in was undone records and notifies nothing`() = runTest {
        val events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
        val attendance = RecordingAttendanceRepository()
        val notifier = RecordingClockNotifier()
        controller(
            events, attendance, notifier,
            MutableStateFlow(ClockNotificationPreference.NOTIFY_UNDO),
        ).start(backgroundScope)
        runCurrent()

        events.emit(ProximityEvent.Arrived("site-a"))
        runCurrent()
        // The user taps Undo on the clock-in notification (what ClockActionReceiver does).
        attendance.undoMostRecent("site-a")

        events.emit(ProximityEvent.Departed("site-a"))
        runCurrent()

        assertTrue(attendance.clockOuts.isEmpty())
        assertTrue(attendance.events.isEmpty())
        assertTrue(notifier.recorded.none { it.type == ClockType.CLOCK_OUT })
    }
}
