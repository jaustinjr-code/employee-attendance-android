package com.jaustinjr.employeeattendance.location.registration

import kotlinx.coroutines.flow.StateFlow

/**
 * Source of the user's registered work locations and which one is currently active for tracking.
 *
 * The real, persistent implementation is [DefaultWorkLocationRepository]; the *act of registering*
 * locations (capturing the current position, geocoding an address, etc.) lives in the registration
 * UI layer, which calls [registerWorkLocation]/[removeWorkLocation] here.
 *
 * ### Single active location
 * Exactly one location is "active" at a time ([activeWorkLocation]); the
 * [com.jaustinjr.employeeattendance.location.LocationFeatureCoordinator] registers a geofence only
 * for that one, matching the proximity engine's single-active-target assumption. Multiple locations
 * may be registered, but only one is watched.
 */
interface WorkLocationRepository {

    /** All registered work locations. */
    val workLocations: StateFlow<List<WorkLocation>>

    /** The location currently being tracked, or null if none is registered/selected. */
    val activeWorkLocation: StateFlow<WorkLocation?>

    /** Selects which registered location is active. No-op if [id] is unknown. */
    fun setActiveWorkLocation(id: String)

    /**
     * Registers (or replaces, by [WorkLocation.id]) a work location. If no location was active, the
     * newly registered one becomes active.
     */
    fun registerWorkLocation(location: WorkLocation)

    /** Removes a registered location; if it was active, the next remaining one becomes active. */
    fun removeWorkLocation(id: String)

    /** Removes all registered locations and clears the active selection. Backs "delete all data". */
    fun clearAll() {}
}
