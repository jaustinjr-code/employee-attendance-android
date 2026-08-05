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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for issue #19: `Application.onCreate()` runs on the main thread, so nothing
 * reachable from it may block on disk or the Android Keystore.
 *
 * In production the repositories handed to [LocationFeatureCoordinator] are `by lazy` container
 * properties whose construction opens an `EncryptedSharedPreferences` store. Merely *reading* such
 * a property therefore does Keystore + disk work on whatever thread does the reading. The pre-fix
 * `start()` built its second pipeline's `combine(...)` at the call site rather than inside
 * `scope.launch`, so `workLocationRepository.activeWorkLocation` was evaluated on the caller's
 * thread — the main thread.
 *
 * Rather than assert on production disk I/O (impossible in a JVM test), this asserts the property
 * that makes the disk I/O safe: `start()` must not touch its collaborators on the calling thread.
 * The fakes here record which thread reads them.
 */
class LocationFeatureCoordinatorThreadingTest {

    private val office = WorkLocation(
        id = "office",
        name = "Office",
        latitudeDegrees = 37.0,
        longitudeDegrees = -122.0,
        radiusMeters = 100f,
    )

    /** Records the thread of every read of the flows the coordinator consumes. */
    private class ThreadRecordingWorkLocationRepository(
        active: WorkLocation?,
    ) : WorkLocationRepository {
        val readThreads = CopyOnWriteArrayList<String>()
        private val _active = MutableStateFlow(active)
        private val _all = MutableStateFlow(listOfNotNull(active))

        override val activeWorkLocation: StateFlow<WorkLocation?>
            get() {
                readThreads += Thread.currentThread().name
                return _active
            }
        override val workLocations: StateFlow<List<WorkLocation>>
            get() {
                readThreads += Thread.currentThread().name
                return _all
            }

        override fun setActiveWorkLocation(id: String) = Unit
        override fun registerWorkLocation(location: WorkLocation) = Unit
        override fun removeWorkLocation(id: String) = Unit
    }

    private class ThreadRecordingPermissionRepository : LocationPermissionRepository {
        val readThreads = CopyOnWriteArrayList<String>()
        private val _state = MutableStateFlow(
            LocationPermissionState(accessLevel = LocationAccessLevel.ALWAYS),
        )
        override val permissionState: StateFlow<LocationPermissionState>
            get() {
                readThreads += Thread.currentThread().name
                return _state
            }
        override fun refresh(): LocationPermissionState = _state.value
    }

    private class NoopGeofenceRegistrar : GeofenceRegistrar {
        override suspend fun register(targets: List<GeofenceTarget>) = Unit
        override suspend fun clear() = Unit
    }

    private class NoopProximityUpdater : ProximityUpdater {
        override fun onLocation(sample: LocationSample, target: GeofenceTarget) = Unit
        override fun reset() = Unit
    }

    private class NoopServiceLauncher : TrackingServiceLauncher {
        override fun start() = Unit
        override fun stop() = Unit
    }

    @Test
    fun `start touches no collaborator on the calling thread`() {
        val workLocations = ThreadRecordingWorkLocationRepository(office)
        val permissions = ThreadRecordingPermissionRepository()
        val locationState = LocationStateRepository()
        val coordinator = LocationFeatureCoordinator(
            permissionRepository = permissions,
            workLocationRepository = workLocations,
            trackingController = LocationTrackingController(
                serviceLauncher = NoopServiceLauncher(),
                locationState = locationState,
            ),
            geofenceRegistrar = NoopGeofenceRegistrar(),
            locationState = locationState,
            proximityUpdater = NoopProximityUpdater(),
        )

        // A real (not Test) dispatcher on a named worker thread. A TestScope with an unconfined
        // dispatcher would run the pipelines inline on the caller, which is exactly what this test
        // must be able to distinguish from.
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, WORKER_THREAD_NAME)
        }
        val scope = CoroutineScope(executor.asCoroutineDispatcher() + Job())
        val callerThread = Thread.currentThread().name

        try {
            coordinator.start(scope)

            // Both pipelines must have got far enough to read the repositories, otherwise the
            // assertion below would pass vacuously.
            awaitUntil { workLocations.readThreads.size >= 2 && permissions.readThreads.isNotEmpty() }

            val offending = (workLocations.readThreads + permissions.readThreads)
                .filter { it.substringBefore(" @") == callerThread }
            assertTrue(
                "start() read its collaborators on the calling thread ($callerThread): " +
                    "$offending. In production that thread is the main thread and the read " +
                    "constructs an EncryptedSharedPreferences store (issue #19).",
                offending.isEmpty(),
            )
            assertTrue(
                "expected the reads to happen on the coordinator's scope thread, saw " +
                    "${workLocations.readThreads}",
                // Coroutine debug mode appends "@coroutine#N" to the thread name, so match the prefix.
                workLocations.readThreads.all { it.startsWith(WORKER_THREAD_NAME) },
            )
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("coordinator pipelines did not read their inputs within ${AWAIT_TIMEOUT_MS}ms")
    }

    private companion object {
        const val WORKER_THREAD_NAME = "coordinator-worker"
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 10L
    }
}
