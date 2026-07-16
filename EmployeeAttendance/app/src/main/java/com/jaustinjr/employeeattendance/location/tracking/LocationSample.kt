package com.jaustinjr.employeeattendance.location.tracking

/**
 * A single, UI-agnostic location fix. Deliberately decoupled from Android's [android.location.Location]
 * so the tracking layer can be tested and consumed without a framework dependency.
 *
 * @param latitudeDegrees WGS84 latitude in degrees.
 * @param longitudeDegrees WGS84 longitude in degrees.
 * @param accuracyMeters estimated horizontal accuracy (68% radius) in meters; smaller is better.
 * @param timestampEpochMillis wall-clock time of the fix, milliseconds since the Unix epoch.
 */
data class LocationSample(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val accuracyMeters: Float,
    val timestampEpochMillis: Long,
)
