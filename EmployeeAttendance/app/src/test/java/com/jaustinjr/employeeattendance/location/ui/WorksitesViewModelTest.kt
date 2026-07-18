package com.jaustinjr.employeeattendance.location.ui

import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockNotifications
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.attendance.LocationAttendance
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorksitesViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakeWorkLocationRepository(seed: List<WorkLocation>) : WorkLocationRepository {
        private val _list = MutableStateFlow(seed)
        override val workLocations: StateFlow<List<WorkLocation>> = _list
        private val _active = MutableStateFlow(seed.firstOrNull())
        override val activeWorkLocation: StateFlow<WorkLocation?> = _active
        override fun setActiveWorkLocation(id: String) {
            _active.value = _list.value.firstOrNull { it.id == id } ?: _active.value
        }
        override fun registerWorkLocation(location: WorkLocation) = Unit
        override fun removeWorkLocation(id: String) = Unit
    }

    private class FakeAttendanceRepository(
        initial: Map<String, LocationAttendance> = emptyMap(),
    ) : AttendanceRepository {
        private val _attendance = MutableStateFlow(initial)
        override val attendance: StateFlow<Map<String, LocationAttendance>> = _attendance
        val clockOuts = mutableListOf<String>()
        override fun recordClockIn(locationId: String, epochMillis: Long, source: com.jaustinjr.employeeattendance.attendance.ClockSource) = Unit
        override fun recordClockOut(locationId: String, epochMillis: Long, source: com.jaustinjr.employeeattendance.attendance.ClockSource) {
            clockOuts += locationId
        }
        override fun undoLast(locationId: String) = Unit
    }

    private class RecordingNotifier : ClockNotifications {
        data class Recorded(val id: String, val type: ClockType, val withUndo: Boolean)
        val recorded = mutableListOf<Recorded>()
        override fun notifyRecorded(worksite: WorkLocation, clockType: ClockType, withUndo: Boolean) {
            recorded += Recorded(worksite.id, clockType, withUndo)
        }
        override fun notifyConfirm(worksite: WorkLocation, clockType: ClockType) = Unit
    }

    private val worksiteA = WorkLocation("A", "Downtown", null, 37.0, -122.0, 150f)
    private val worksiteB = WorkLocation("B", "Warehouse", null, 37.8, -122.3, 150f)

    @Test
    fun `activeClockedIn reflects attendance for the active worksite`() = runTest {
        val attendance = FakeAttendanceRepository(
            mapOf("A" to LocationAttendance(lastClockInMillis = 1_000L)),
        )
        val model = WorksitesViewModel(
            FakeWorkLocationRepository(listOf(worksiteA, worksiteB)),
            attendance,
            RecordingNotifier(),
        )
        backgroundScope.launch { model.uiState.collect {} }
        runCurrent()

        assertTrue(model.uiState.value.activeClockedIn)
    }

    @Test
    fun `confirming a switch away from a clocked-in worksite clocks it out and notifies`() = runTest {
        val work = FakeWorkLocationRepository(listOf(worksiteA, worksiteB))
        val attendance = FakeAttendanceRepository(
            mapOf("A" to LocationAttendance(lastClockInMillis = 1_000L)),
        )
        val notifier = RecordingNotifier()
        val model = WorksitesViewModel(work, attendance, notifier)

        model.onConfirmSwitchActive("B")

        // Clocked out of the PREVIOUS worksite (A), not the new one, with a matching notification.
        assertEquals(listOf("A"), attendance.clockOuts)
        assertEquals(
            listOf(RecordingNotifier.Recorded("A", ClockType.CLOCK_OUT, withUndo = false)),
            notifier.recorded,
        )
        assertEquals("B", work.activeWorkLocation.value?.id)
    }

    @Test
    fun `confirming a switch when not clocked in just transfers`() = runTest {
        val work = FakeWorkLocationRepository(listOf(worksiteA, worksiteB))
        val attendance = FakeAttendanceRepository() // nothing clocked in
        val notifier = RecordingNotifier()
        val model = WorksitesViewModel(work, attendance, notifier)

        model.onConfirmSwitchActive("B")

        assertTrue(attendance.clockOuts.isEmpty())
        assertTrue(notifier.recorded.isEmpty())
        assertEquals("B", work.activeWorkLocation.value?.id)
    }

    @Test
    fun `onSetActive transfers without any clock-out`() = runTest {
        val work = FakeWorkLocationRepository(listOf(worksiteA, worksiteB))
        val attendance = FakeAttendanceRepository(
            mapOf("A" to LocationAttendance(lastClockInMillis = 1_000L)),
        )
        val notifier = RecordingNotifier()
        val model = WorksitesViewModel(work, attendance, notifier)

        model.onSetActive("B")

        assertTrue(attendance.clockOuts.isEmpty())
        assertFalse(notifier.recorded.isNotEmpty())
        assertEquals("B", work.activeWorkLocation.value?.id)
    }
}
