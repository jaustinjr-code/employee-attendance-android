package com.jaustinjr.employeeattendance.location.registration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Source of the user's registered work locations and which one is currently active for tracking.
 *
 * The *act of registering* locations (searching a map, confirming an address, etc.) is a separate
 * flow that is intentionally out of scope here — see [StubWorkLocationRepository], which stands in
 * with a single pre-registered location so the location/proximity feature can be built and
 * exercised end to end.
 */
interface WorkLocationRepository {

    /** All registered work locations. */
    val workLocations: StateFlow<List<WorkLocation>>

    /** The location currently being tracked, or null if none is registered/selected. */
    val activeWorkLocation: StateFlow<WorkLocation?>

    /** Selects which registered location is active. No-op if [id] is unknown. */
    fun setActiveWorkLocation(id: String)

    /**
     * Stub seam for the separate registration flow. A real implementation would persist the
     * location and surface it here.
     */
    fun registerWorkLocation(location: WorkLocation)

    /** Stub seam for removing a registered location. */
    fun removeWorkLocation(id: String)
}

/**
 * In-memory [WorkLocationRepository] seeded with one mock location ("Downtown Office"). It exists so
 * the rest of the feature has a concrete work location to track against while the real registration
 * flow is unimplemented. State is not persisted and resets on process death.
 */
class StubWorkLocationRepository : WorkLocationRepository {

    private val _workLocations = MutableStateFlow(listOf(DEFAULT_OFFICE))
    override val workLocations: StateFlow<List<WorkLocation>> = _workLocations.asStateFlow()

    private val _activeWorkLocation = MutableStateFlow<WorkLocation?>(DEFAULT_OFFICE)
    override val activeWorkLocation: StateFlow<WorkLocation?> = _activeWorkLocation.asStateFlow()

    override fun setActiveWorkLocation(id: String) {
        _activeWorkLocation.value = _workLocations.value.firstOrNull { it.id == id }
            ?: _activeWorkLocation.value
    }

    override fun registerWorkLocation(location: WorkLocation) {
        _workLocations.value = _workLocations.value.filterNot { it.id == location.id } + location
        if (_activeWorkLocation.value == null) {
            _activeWorkLocation.value = location
        }
    }

    override fun removeWorkLocation(id: String) {
        _workLocations.value = _workLocations.value.filterNot { it.id == id }
        if (_activeWorkLocation.value?.id == id) {
            _activeWorkLocation.value = _workLocations.value.firstOrNull()
        }
    }

    companion object {
        /** Mock pre-registered office used until the real registration flow exists. */
        val DEFAULT_OFFICE = WorkLocation(
            id = "downtown-office",
            name = "Downtown Office",
            address = "123 Market St",
            latitudeDegrees = 37.7749,
            longitudeDegrees = -122.4194,
            radiusMeters = 150f,
        )
    }
}
