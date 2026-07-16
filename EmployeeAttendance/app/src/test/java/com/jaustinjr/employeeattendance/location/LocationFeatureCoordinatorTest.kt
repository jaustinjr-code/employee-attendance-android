package com.jaustinjr.employeeattendance.location

import com.jaustinjr.employeeattendance.location.geofence.GeofenceRegistrar
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.proximity.GeofenceTarget
import com.jaustinjr.employeeattendance.location.proximity.ProximityUpdater
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationTrackingController
import com.jaustinjr.employeeattendance.location.tracking.TrackingServiceLauncher
import com.jaustinjr.employeeattendance.location.tracking.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationFeatureCoordinatorTest {

    private val office = WorkLocation(
        id = "office",
        name = "Office",
        latitudeDegrees = 37.0,
        longitudeDegrees = -122.0,
        radiusMeters = 100f,
    )

    private class FakePermissionRepository(
        initial: LocationPermissionState,
    ) : LocationPermissionRepository {
        private val _state = MutableStateFlow(initial)
        override val permissionState: StateFlow<LocationPermissionState> = _state
        override fun refresh(): LocationPermissionState = _state.value
        fun set(state: LocationPermissionState) { _state.value = state }
    }

    private class FakeWorkLocationRepository(
        active: WorkLocation?,
    ) : WorkLocationRepository {
        private val _active = MutableStateFlow(active)
        override val activeWorkLocation: StateFlow<WorkLocation?> = _active
        override val workLocations: StateFlow<List<WorkLocation>> =
            MutableStateFlow(listOfNotNull(active))
        override fun setActiveWorkLocation(id: String) = Unit
        override fun registerWorkLocation(location: WorkLocation) = Unit
        override fun removeWorkLocation(id: String) = Unit
        fun setActive(location: WorkLocation?) { _active.value = location }
    }

    private class FakeGeofenceRegistrar : GeofenceRegistrar {
        var registered: List<GeofenceTarget>? = null
        var clearCount = 0
        var throwOnRegister = false
        override suspend fun register(targets: List<GeofenceTarget>) {
            if (throwOnRegister) throw IllegalStateException("play services unavailable")
            registered = targets
        }
        override suspend fun clear() { clearCount++ }
    }

    private class FakeProximityUpdater : ProximityUpdater {
        var onLocationCalls = 0
        var lastTarget: GeofenceTarget? = null
        var resetCount = 0
        override fun onLocation(sample: LocationSample, target: GeofenceTarget) {
            onLocationCalls++
            lastTarget = target
        }
        override fun reset() { resetCount++ }
    }

    private class RecordingLauncher : TrackingServiceLauncher {
        var startCount = 0
        var stopCount = 0
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
    }

    private fun permission(level: LocationAccessLevel) =
        LocationPermissionState(level, isPrecise = true)

    private fun sample() = LocationSample(37.0, -122.0, 5f, 1_000L)

    /** An eager scope so the coordinator's flow pipelines run inline and can be observed. */
    private fun TestScope.runningScope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `always with a location registers a geofence and starts tracking`() = runTest {
        val launcher = RecordingLauncher()
        val geofences = FakeGeofenceRegistrar()
        val coordinator = LocationFeatureCoordinator(
            permissionRepository = FakePermissionRepository(permission(LocationAccessLevel.ALWAYS)),
            workLocationRepository = FakeWorkLocationRepository(office),
            trackingController = LocationTrackingController(launcher, LocationStateRepository()),
            geofenceRegistrar = geofences,
            locationState = LocationStateRepository(),
            proximityUpdater = FakeProximityUpdater(),
        )

        coordinator.start(runningScope())
        runCurrent()

        assertEquals(1, launcher.startCount)
        assertEquals(listOf(office.toGeofenceTarget()), geofences.registered)
    }

    @Test
    fun `when-in-use clears geofences and reports foreground-only`() = runTest {
        val launcher = RecordingLauncher()
        val geofences = FakeGeofenceRegistrar()
        val state = LocationStateRepository()
        val coordinator = LocationFeatureCoordinator(
            permissionRepository = FakePermissionRepository(permission(LocationAccessLevel.WHEN_IN_USE)),
            workLocationRepository = FakeWorkLocationRepository(office),
            trackingController = LocationTrackingController(launcher, state),
            geofenceRegistrar = geofences,
            locationState = state,
            proximityUpdater = FakeProximityUpdater(),
        )

        coordinator.start(runningScope())
        runCurrent()

        assertNull(geofences.registered)
        assertTrue(geofences.clearCount >= 1)
        assertEquals(TrackingStatus.FOREGROUND_ONLY, state.trackingStatus.value)
    }

    @Test
    fun `null active location resets proximity and clears geofences`() = runTest {
        val geofences = FakeGeofenceRegistrar()
        val proximity = FakeProximityUpdater()
        val coordinator = LocationFeatureCoordinator(
            permissionRepository = FakePermissionRepository(permission(LocationAccessLevel.ALWAYS)),
            workLocationRepository = FakeWorkLocationRepository(active = null),
            trackingController = LocationTrackingController(RecordingLauncher(), LocationStateRepository()),
            geofenceRegistrar = geofences,
            locationState = LocationStateRepository(),
            proximityUpdater = proximity,
        )

        coordinator.start(runningScope())
        runCurrent()

        assertTrue(geofences.clearCount >= 1)
        assertTrue(proximity.resetCount >= 1)
    }

    @Test
    fun `a fix with an active location feeds proximity`() = runTest {
        val state = LocationStateRepository()
        val proximity = FakeProximityUpdater()
        val coordinator = LocationFeatureCoordinator(
            permissionRepository = FakePermissionRepository(permission(LocationAccessLevel.ALWAYS)),
            workLocationRepository = FakeWorkLocationRepository(office),
            trackingController = LocationTrackingController(RecordingLauncher(), state),
            geofenceRegistrar = FakeGeofenceRegistrar(),
            locationState = state,
            proximityUpdater = proximity,
        )

        coordinator.start(runningScope())
        state.publishLocation(sample())
        runCurrent()

        assertEquals(1, proximity.onLocationCalls)
        assertEquals(office.toGeofenceTarget(), proximity.lastTarget)
    }

    @Test
    fun `a geofence registration failure does not break the pipeline`() = runTest {
        val geofences = FakeGeofenceRegistrar().apply { throwOnRegister = true }
        val permissions = FakePermissionRepository(permission(LocationAccessLevel.ALWAYS))
        val locations = FakeWorkLocationRepository(office)
        val coordinator = LocationFeatureCoordinator(
            permissionRepository = permissions,
            workLocationRepository = locations,
            trackingController = LocationTrackingController(RecordingLauncher(), LocationStateRepository()),
            geofenceRegistrar = geofences,
            locationState = LocationStateRepository(),
            proximityUpdater = FakeProximityUpdater(),
        )

        coordinator.start(runningScope())
        runCurrent()
        // The register threw and was swallowed; a later change is still reconciled.
        permissions.set(permission(LocationAccessLevel.NONE))
        runCurrent()

        assertTrue(geofences.clearCount >= 1)
    }
}
