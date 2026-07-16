package com.jaustinjr.employeeattendance.location.geofence

import com.jaustinjr.employeeattendance.location.proximity.GeofenceTarget

/**
 * Seam over OS geofence registration, so the reconciliation policy in
 * [com.jaustinjr.employeeattendance.location.LocationFeatureCoordinator] can be unit-tested without
 * Play Services. Implemented by [GeofenceManager] in production.
 */
interface GeofenceRegistrar {
    /** Replaces the currently registered geofences with [targets] (empty clears all). */
    suspend fun register(targets: List<GeofenceTarget>)

    /** Removes all geofences registered through this registrar. */
    suspend fun clear()
}
