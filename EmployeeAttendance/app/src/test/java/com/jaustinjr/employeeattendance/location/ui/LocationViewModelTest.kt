package com.jaustinjr.employeeattendance.location.ui

import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.proximity.ProximityStateStore
import com.jaustinjr.employeeattendance.location.registration.LocationClockInRepository
import com.jaustinjr.employeeattendance.location.registration.StubWorkLocationRepository
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationRequestConfig
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationTracker
import com.jaustinjr.employeeattendance.location.tracking.LocationPriority
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

    private class NullActiveWorkLocationRepository : WorkLocationRepository {
        override val workLocations: StateFlow<List<WorkLocation>> = MutableStateFlow(emptyList())
        override val activeWorkLocation: StateFlow<WorkLocation?> = MutableStateFlow(null)
        override fun setActiveWorkLocation(id: String) = Unit
        override fun registerWorkLocation(location: WorkLocation) = Unit
        override fun removeWorkLocation(id: String) = Unit
    }

    private val store = object : ProximityStateStore {
        override fun load() = ProximityState.UNKNOWN
        override fun loadTargetId(): String? = null
        override fun save(state: ProximityState, targetId: String?) = Unit
    }

    private fun state(level: LocationAccessLevel) =
        LocationPermissionState(level, isPrecise = level != LocationAccessLevel.NONE)

    private fun viewModel(
        workLocations: WorkLocationRepository = StubWorkLocationRepository(),
        permission: LocationAccessLevel = LocationAccessLevel.ALWAYS,
        tracker: LocationTracker = FakeLocationTracker(),
        clockIns: LocationClockInRepository = LocationClockInRepository(),
    ) = LocationViewModel(
        workLocationRepository = workLocations,
        proximityRepository = ProximityRepository(store),
        locationStateRepository = LocationStateRepository(),
        permissionRepository = FakePermissionRepository(state(permission)),
        locationTracker = tracker,
        clockInRepository = clockIns,
    )

    @Test
    fun `uiState reflects location, access level, and last clock-in`() = runTest {
        val clockIns = LocationClockInRepository()
        clockIns.recordClockIn(StubWorkLocationRepository.DEFAULT_OFFICE.id, 5_000L)
        val vm = viewModel(permission = LocationAccessLevel.ALWAYS, clockIns = clockIns)

        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        val ui = vm.uiState.value
        assertEquals(StubWorkLocationRepository.DEFAULT_OFFICE, ui.activeWorkLocation)
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
        val clockIns = LocationClockInRepository()
        val vm = viewModel(clockIns = clockIns)

        vm.onClockIn()

        assertTrue(clockIns.lastClockIns.value.containsKey(StubWorkLocationRepository.DEFAULT_OFFICE.id))
    }

    @Test
    fun `onClockIn is a no-op when there is no active location`() = runTest {
        val clockIns = LocationClockInRepository()
        val vm = viewModel(workLocations = NullActiveWorkLocationRepository(), clockIns = clockIns)

        vm.onClockIn()

        assertTrue(clockIns.lastClockIns.value.isEmpty())
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
}
