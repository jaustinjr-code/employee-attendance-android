package com.jaustinjr.employeeattendance.location.registration

import com.jaustinjr.employeeattendance.location.proximity.GeofenceTarget

/**
 * A work location the user has registered for attendance tracking. This is the domain-facing model
 * (it carries a display name and address) as opposed to the purely geometric [GeofenceTarget] the
 * proximity engine consumes.
 *
 * @param id stable identifier, reused as the geofence request id.
 * @param name human-readable label shown in the UI, e.g. "Downtown Office".
 * @param address optional address line for display; not used for geofencing.
 * @param latitudeDegrees center latitude (WGS84).
 * @param longitudeDegrees center longitude (WGS84).
 * @param radiusMeters trigger radius around the center.
 */
data class WorkLocation(
    val id: String,
    val name: String,
    val address: String? = null,
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val radiusMeters: Float,
) {
    /** Projects this registered location down to the geometric target the proximity engine uses. */
    fun toGeofenceTarget(): GeofenceTarget = GeofenceTarget(
        id = id,
        latitudeDegrees = latitudeDegrees,
        longitudeDegrees = longitudeDegrees,
        radiusMeters = radiusMeters,
    )
}
