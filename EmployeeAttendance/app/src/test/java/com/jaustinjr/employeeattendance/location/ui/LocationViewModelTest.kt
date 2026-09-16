package com.jaustinjr.employeeattendance.location.ui

import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.RecordingAttendanceRepository
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.proximity.ProximityStateStore
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationRequestConfig
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationTracker
import com.jaustinjr.employeeattendance.location.tracking.LocationPriority
import com.jaustinjr.employeeattendance.statusupdate.DefaultStatusUpdateRepository
import com.jaustinjr.employeeattendance.statusupdate.FakeAppForegroundTracker
import com.jaustinjr.employeeattendance.statusupdate.RecordingStatusUpdateNotifier
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateCoordinator
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakePermissionRepository(
        initial: LocationPermissionState,
    ) : LocationPermissionRepository {
        private val _state = MutableStateFlow(initial)
        override val permissionState: StateFlow<LocationPermissionState> = _state
        override fun refresh(): LocationPermissionState = _state.value
    }

    private class FakeLocationTracker : LocationTracker {
        var updatesCallCount = 0
        var lastConfig: LocationRequestConfig? = null
        override fun locationUpdates(config: LocationRequestConfig): Flow<LocationSample> {
            updatesCallCount++
            lastConfig = config
            return emptyFlow()
        }
        override suspend fun currentLocation(priority: LocationPriority): LocationSample? = null
    }

    /** In-memory work-location repo seeded with one active location for the tests. */
    private class SeededWorkLocationRepository(
        seed: WorkLocation? = TEST_OFFICE,
    ) : WorkLocationRepository {
        private val _list = MutableStateFlow(listOfNotNull(seed))
        override val workLocations: StateFlow<List<WorkLocation>> = _list
        private val _active = MutableStateFlow(seed)
        override val activeWorkLocation: StateFlow<WorkLocation?> = _active
        override fun setActiveWorkLocation(id: String) {
            _active.value = _list.value.firstOrNull { it.id == id } ?: _active.value
        }
        override fun registerWorkLocation(location: WorkLocation) {
            _list.value = _list.value.filterNot { it.id == location.id } + location
            if (_active.value == null) _active.value = location
        }
        override fun removeWorkLocation(id: String) {
            _list.value = _list.value.filterNot { it.id == id }
            if (_active.value?.id == id) _active.value = _list.value.firstOrNull()
        }
    }

    private val store = object : ProximityStateStore {
        override fun load() = ProximityState.UNKNOWN
        override fun loadTargetId(): String? = null
        override fun save(state: ProximityState, targetId: String?) = Unit
    }

    private fun state(level: LocationAccessLevel) =
        LocationPermissionState(level, isPrecise = level != LocationAccessLevel.NONE)

    private fun fakeStatusUpdateCoordinator(
        enabled: Boolean = true,
        notifier: RecordingStatusUpdateNotifier = RecordingStatusUpdateNotifier(),
        attendanceRepository: AttendanceRepository = RecordingAttendanceRepository(),
    ) = StatusUpdateCoordinator(
        enabled = MutableStateFlow(enabled),
        foregroundTracker = FakeAppForegroundTracker(initial = true),
        notifier = notifier,
        repository = DefaultStatusUpdateRepository(),
        attendanceRepository = attendanceRepository,
    )

    private fun viewModel(
        workLocations: WorkLocationRepository = SeededWorkLocationRepository(),
        permission: LocationAccessLevel = LocationAccessLevel.ALWAYS,
        tracker: LocationTracker = FakeLocationTracker(),
        attendance: AttendanceRepository = RecordingAttendanceRepository(),
        statusUpdateCoordinator: StatusUpdateCoordinator = fakeStatusUpdateCoordinator(),
    ) = LocationViewModel(
        workLocationRepository = workLocations,
        proximityRepository = ProximityRepository(store),
        locationStateRepository = LocationStateRepository(),
        permissionRepository = FakePermissionRepository(state(permission)),
        locationTracker = tracker,
        attendanceRepository = attendance,
        statusUpdateCoordinator = statusUpdateCoordinator,
    )

    @Test
    fun `uiState reflects location, access level, and last clock-in`() = runTest {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn(TEST_OFFICE.id, 5_000L)
        val vm = viewModel(permission = LocationAccessLevel.ALWAYS, attendance = attendance)

        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        val ui = vm.uiState.value
        assertEquals(TEST_OFFICE, ui.activeWorkLocation)
        assertEquals(LocationAccessLevel.ALWAYS, ui.accessLevel)
        assertEquals(5_000L, ui.lastClockInEpochMillis)
        assertTrue(ui.isSetUp)
        assertTrue(ui.canShowMap)
    }

    @Test
    fun `when-in-use is set up but cannot show the map`() = runTest {
        val vm = viewModel(permission = LocationAccessLevel.WHEN_IN_USE)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        val ui = vm.uiState.value
        assertTrue(ui.isSetUp)
        assertFalse(ui.canShowMap)
    }

    @Test
    fun `onClockIn records against the active location`() = runTest {
        val attendance = RecordingAttendanceRepository()
        val vm = viewModel(attendance = attendance)

        vm.onClockIn()

        assertTrue(attendance.attendance.value.containsKey(TEST_OFFICE.id))
    }

    @Test
    fun `onClockOut records a manual clock-out against the active location`() = runTest {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn(TEST_OFFICE.id, 1_000L)
        val vm = viewModel(attendance = attendance)

        vm.onClockOut()

        assertTrue(attendance.attendance.value[TEST_OFFICE.id]?.lastClockOutManual == true)
    }

    @Test
    fun `onClockOut reports a MANUAL trigger to the status update coordinator`() = runTest {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn(TEST_OFFICE.id, 1_000L)
        val coordinator = fakeStatusUpdateCoordinator(attendanceRepository = attendance)
        val vm = viewModel(attendance = attendance, statusUpdateCoordinator = coordinator)

        vm.onClockOut()

        // MANUAL always shows the pop-up, so a live pending prompt proves the coordinator was
        // reached with the MANUAL trigger.
        assertEquals(TEST_OFFICE.id, coordinator.pendingPrompt.value?.clockOutId?.substringBefore('@'))
    }

    @Test
    fun `onClockOut does nothing to the coordinator when status update is disabled`() = runTest {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn(TEST_OFFICE.id, 1_000L)
        val notifier = RecordingStatusUpdateNotifier()
        val coordinator = fakeStatusUpdateCoordinator(
            enabled = false, notifier = notifier, attendanceRepository = attendance,
        )
        val vm = viewModel(attendance = attendance, statusUpdateCoordinator = coordinator)

        vm.onClockOut()

        assertNull(coordinator.pendingPrompt.value)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `a redundant onClockOut while not clocked in records nothing and leaves no prompt`() = runTest {
        val attendance = RecordingAttendanceRepository()
        val coordinator = fakeStatusUpdateCoordinator(attendanceRepository = attendance)
        val vm = viewModel(attendance = attendance, statusUpdateCoordinator = coordinator)

        // Never clocked in, so this clock-out is redundant.
        vm.onClockOut()

        assertTrue(attendance.events.isEmpty())
        assertNull(coordinator.pendingPrompt.value)
    }

    @Test
    fun `onClockIn records against the general timeclock when no worksite is active`() = runTest {
        val attendance = RecordingAttendanceRepository()
        val vm = viewModel(
            workLocations = SeededWorkLocationRepository(seed = null),
            attendance = attendance,
        )

        vm.onClockIn()

        // Usable as a plain timeclock with no worksite: records against the general id.
        assertTrue(
            attendance.attendance.value.containsKey(
                com.jaustinjr.employeeattendance.attendance.AttendanceRepository.GENERAL_TIMECLOCK_ID,
            ),
        )
    }

    @Test
    fun `foreground collection is off under full access`() = runTest {
        val tracker = FakeLocationTracker()
        viewModel(permission = LocationAccessLevel.ALWAYS, tracker = tracker)
        runCurrent()

        assertEquals(0, tracker.updatesCallCount)
    }

    @Test
    fun `foreground collection runs under when-in-use`() = runTest {
        val tracker = FakeLocationTracker()
        viewModel(permission = LocationAccessLevel.WHEN_IN_USE, tracker = tracker)
        runCurrent()

        assertTrue(tracker.updatesCallCount >= 1)
    }

    @Test
    fun `foreground collection is off with no permission`() = runTest {
        val tracker = FakeLocationTracker()
        viewModel(permission = LocationAccessLevel.NONE, tracker = tracker)
        runCurrent()

        assertEquals(0, tracker.updatesCallCount)
    }

    private companion object {
        val TEST_OFFICE = WorkLocation(
            id = "downtown-office",
            name = "Downtown Office",
            address = "123 Market St",
            latitudeDegrees = 37.7749,
            longitudeDegrees = -122.4194,
            radiusMeters = 150f,
        )
    }
}
