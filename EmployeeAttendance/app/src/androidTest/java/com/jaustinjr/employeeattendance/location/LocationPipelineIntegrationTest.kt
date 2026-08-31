package com.jaustinjr.employeeattendance.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.location.geofence.GeofenceRegistrar
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.proximity.GeofenceTarget
import com.jaustinjr.employeeattendance.location.proximity.ProximityCalculator
import com.jaustinjr.employeeattendance.location.proximity.ProximityEvent
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.proximity.SharedPrefsProximityStateStore
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationTrackingController
import com.jaustinjr.employeeattendance.location.tracking.TrackingServiceLauncher
import com.jaustinjr.employeeattendance.testutil.clearPersistedProximityState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration coverage for the foreground half of the location pipeline, wired the way the app wires
 * it: a real [ProximityRepository] over real SharedPreferences and a real [LocationStateRepository],
 * driven by a real [LocationFeatureCoordinator]. Only the edges the coordinator talks *out* through
 * — permissions, the service launcher, the geofence registrar — and the work-location store are
 * faked.
 *
 * This runs on a device rather than the JVM for a specific reason: [ProximityCalculator.distanceMeters]
 * delegates to `android.location.Location.distanceBetween`, which is a native framework call. Under
 * the local unit tests' `isReturnDefaultValues = true` it silently returns 0, so every fix would look
 * like it were sitting exactly on the target. The distance math and everything downstream of it are
 * therefore only meaningfully exercised here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationPipelineIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val office = WorkLocation(
        id = "downtown-office",
        name = "Downtown Office",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    @Before
    @After
    fun clearPersistedProximity() {
        clearPersistedProximityState(context)
    }

    private class FakePermissionRepository(initial: LocationAccessLevel) : LocationPermissionRepository {
        private val _state = MutableStateFlow(
            LocationPermissionState(initial, isPrecise = initial != LocationAccessLevel.NONE),
        )
        override val permissionState: StateFlow<LocationPermissionState> = _state
        override fun refresh(): LocationPermissionState = _state.value
        fun set(level: LocationAccessLevel) {
            _state.value = LocationPermissionState(level, isPrecise = level != LocationAccessLevel.NONE)
        }
    }

    /**
     * In-memory [WorkLocationRepository]. The production implementation persists through a data
     * source stack that is not what this test is about; registering a location here is just the
     * input that starts the pipeline.
     */
    private class InMemoryWorkLocationRepository : WorkLocationRepository {
        private val _workLocations = MutableStateFlow<List<WorkLocation>>(emptyList())
        override val workLocations: StateFlow<List<WorkLocation>> = _workLocations
        private val _active = MutableStateFlow<WorkLocation?>(null)
        override val activeWorkLocation: StateFlow<WorkLocation?> = _active

        override fun setActiveWorkLocation(id: String) {
            _workLocations.value.firstOrNull { it.id == id }?.let { _active.value = it }
        }

        override fun registerWorkLocation(location: WorkLocation) {
            _workLocations.value = _workLocations.value.filterNot { it.id == location.id } + location
            if (_active.value == null) _active.value = location
        }

        override fun removeWorkLocation(id: String) {
            _workLocations.value = _workLocations.value.filterNot { it.id == id }
            if (_active.value?.id == id) _active.value = _workLocations.value.firstOrNull()
        }

        override fun clearAll() {
            _workLocations.value = emptyList()
            _active.value = null
        }
    }

    private class RecordingGeofenceRegistrar : GeofenceRegistrar {
        val registered = mutableListOf<List<GeofenceTarget>>()
        var clearCount = 0
        override suspend fun register(targets: List<GeofenceTarget>) { registered += targets }
        override suspend fun clear() { clearCount++ }
    }

    private class RecordingServiceLauncher : TrackingServiceLauncher {
        var started = 0
        var stopped = 0
        override fun start() { started++ }
        override fun stop() { stopped++ }
    }

    /**
     * A fix roughly 1.1 km north of the office — comfortably outside the 150 m radius and its exit
     * buffer, so it must read OUTSIDE once the real geodesy runs.
     */
    private fun fixNorthOfOffice(offsetDegrees: Double) = LocationSample(
        latitudeDegrees = office.latitudeDegrees + offsetDegrees,
        longitudeDegrees = office.longitudeDegrees,
        accuracyMeters = 5f,
        timestampEpochMillis = 1_716_552_000_000L,
    )

    @Test
    fun distanceBetweenRealCoordinatesIsComputed() {
        // ~0.01 degrees of latitude is ~1.11 km; the JVM stub would report 0 here.
        val distance = ProximityCalculator.distanceMeters(
            sample = fixNorthOfOffice(0.01),
            target = office.toGeofenceTarget(),
        )
        assertTrue("expected ~1100m but was ${distance}m", distance in 1_000f..1_250f)
    }

    @Test
    fun arrivingAtTheOfficeEmitsArrivedAndPersistsInside() = runTest {
        val store = SharedPrefsProximityStateStore(context)
        val proximity = ProximityRepository(store)
        val workLocations = InMemoryWorkLocationRepository()
        val locationState = LocationStateRepository()
        val permissions = FakePermissionRepository(LocationAccessLevel.WHEN_IN_USE)
        val registrar = RecordingGeofenceRegistrar()
        val launcher = RecordingServiceLauncher()

        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { proximity.events.collect(events::add) }

        LocationFeatureCoordinator(
            permissionRepository = permissions,
            workLocationRepository = workLocations,
            trackingController = LocationTrackingController(launcher, locationState),
            geofenceRegistrar = registrar,
            locationState = locationState,
            proximityUpdater = proximity,
        ).start(backgroundScope)

        workLocations.registerWorkLocation(office)
        workLocations.setActiveWorkLocation(office.id)
        runCurrent()

        // A fix on top of the office must resolve to INSIDE through the real distance calculation.
        locationState.publishLocation(fixNorthOfOffice(0.0))
        runCurrent()

        assertEquals(ProximityState.INSIDE, proximity.proximity.value)
        assertTrue("events were $events", events.contains(ProximityEvent.Arrived(office.id)))
        // Persisted, so a cold start triggered by a geofence still knows we were inside.
        assertEquals(ProximityState.INSIDE, store.load())

        // Walking ~1.1 km away is outside the radius plus its hysteresis buffer.
        locationState.publishLocation(fixNorthOfOffice(0.01))
        runCurrent()

        assertEquals(ProximityState.OUTSIDE, proximity.proximity.value)
        assertTrue("events were $events", events.contains(ProximityEvent.Departed(office.id)))
    }

    @Test
    fun geofencesAreRegisteredOnlyUnderBackgroundAccess() = runTest {
        val proximity = ProximityRepository(SharedPrefsProximityStateStore(context))
        val workLocations = InMemoryWorkLocationRepository()
        val locationState = LocationStateRepository()
        val permissions = FakePermissionRepository(LocationAccessLevel.WHEN_IN_USE)
        val registrar = RecordingGeofenceRegistrar()
        val launcher = RecordingServiceLauncher()

        LocationFeatureCoordinator(
            permissionRepository = permissions,
            workLocationRepository = workLocations,
            trackingController = LocationTrackingController(launcher, locationState),
            geofenceRegistrar = registrar,
            locationState = locationState,
            proximityUpdater = proximity,
        ).start(backgroundScope)

        workLocations.registerWorkLocation(office)
        workLocations.setActiveWorkLocation(office.id)
        runCurrent()

        // When-in-use: a background service would be killed, and geofences would be useless.
        assertTrue(registrar.registered.isEmpty())
        assertEquals(0, launcher.started)

        permissions.set(LocationAccessLevel.ALWAYS)
        runCurrent()

        assertEquals(listOf(listOf(office.toGeofenceTarget())), registrar.registered)
        assertTrue("service should be started under ALWAYS", launcher.started >= 1)
    }
}
