package com.jaustinjr.employeeattendance.location.proximity

/**
 * A circular geographic region to watch for enter/exit, expressed purely geometrically. This is the
 * input the proximity/geofencing engine operates on; the richer "registered work location" concept
 * (name, address, etc.) lives in the registration layer and maps down to this.
 *
 * @param id stable identifier, reused as the geofence request id and in proximity events.
 * @param latitudeDegrees center latitude (WGS84).
 * @param longitudeDegrees center longitude (WGS84).
 * @param radiusMeters trigger radius around the center; a user is "inside" within this distance.
 */
data class GeofenceTarget(
    val id: String,
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val radiusMeters: Float,
) {
    init {
        require(id.isNotBlank()) { "GeofenceTarget id must not be blank" }
        require(latitudeDegrees in -90.0..90.0) { "latitude out of range: $latitudeDegrees" }
        require(longitudeDegrees in -180.0..180.0) { "longitude out of range: $longitudeDegrees" }
        require(radiusMeters > 0f) { "radiusMeters must be positive" }
    }
}
