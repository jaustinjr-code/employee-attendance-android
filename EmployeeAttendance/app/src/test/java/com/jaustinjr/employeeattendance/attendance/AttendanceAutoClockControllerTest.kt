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
        var recordedCount = 0
        var confirmCount = 0
        override fun notifyRecorded(worksite: WorkLocation, clockType: ClockType, withUndo: Boolean) {
            recordedCount++
        }
        override fun notifyConfirm(worksite: WorkLocation, clockType: ClockType) { confirmCount++ }
    }

    @Test
    fun `arrived and departed drive clock-in and clock-out under the notify-undo preference`() = runTest {
        val events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
        val attendance = RecordingAttendance()
        val notifier = RecordingNotifier()
        val controller = AttendanceAutoClockController(
            proximityEvents = events,
            workLocationRepository = SingleWorkLocationRepository(worksite),
            attendanceRepository = attendance,
            notifier = notifier,
            preference = MutableStateFlow(ClockNotificationPreference.NOTIFY_UNDO),
        )
        controller.start(backgroundScope)
        runCurrent()

        events.emit(ProximityEvent.Arrived("site-a"))
        events.emit(ProximityEvent.Departed("site-a"))
        runCurrent()

        assertEquals(listOf("site-a"), attendance.clockIns)
        assertEquals(listOf("site-a"), attendance.clockOuts)
        assertEquals(2, notifier.recordedCount)
    }

    @Test
    fun `changing preference switches strategy on the next event`() = runTest {
        val events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
        val attendance = RecordingAttendance()
        val notifier = RecordingNotifier()
        val preference = MutableStateFlow(ClockNotificationPreference.CONFIRM)
        val controller = AttendanceAutoClockController(
            proximityEvents = events,
            workLocationRepository = SingleWorkLocationRepository(worksite),
            attendanceRepository = attendance,
            notifier = notifier,
            preference = preference,
        )
        controller.start(backgroundScope)
        runCurrent()

        // Confirm mode: prompt, record nothing.
        events.emit(ProximityEvent.Arrived("site-a"))
        runCurrent()
        assertTrue(attendance.clockIns.isEmpty())
        assertEquals(1, notifier.confirmCount)

        // Switch to silent: next arrival records with no notification.
        preference.value = ClockNotificationPreference.SILENT
        events.emit(ProximityEvent.Arrived("site-a"))
        runCurrent()
        assertEquals(listOf("site-a"), attendance.clockIns)
    }

    @Test
    fun `events for an unknown target are ignored`() = runTest {
        val events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
        val attendance = RecordingAttendance()
        val notifier = RecordingNotifier()
        val controller = AttendanceAutoClockController(
            proximityEvents = events,
            workLocationRepository = SingleWorkLocationRepository(worksite),
            attendanceRepository = attendance,
            notifier = notifier,
            preference = MutableStateFlow(ClockNotificationPreference.SILENT),
        )
        controller.start(backgroundScope)
        runCurrent()

        events.emit(ProximityEvent.Arrived("some-other-site"))
        runCurrent()

        assertTrue(attendance.clockIns.isEmpty())
    }
}
