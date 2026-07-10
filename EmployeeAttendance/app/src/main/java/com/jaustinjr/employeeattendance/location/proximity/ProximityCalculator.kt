package com.jaustinjr.employeeattendance.location.proximity

import android.location.Location
import com.jaustinjr.employeeattendance.location.tracking.LocationSample

/**
 * Pure geometry for proximity decisions. Separated from Android/service concerns so the rules are
 * trivially unit-testable.
 */
object ProximityCalculator {

    /** Great-circle distance in meters between a fix and a target center (WGS84 ellipsoid). */
    fun distanceMeters(sample: LocationSample, target: GeofenceTarget): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            sample.latitudeDegrees,
            sample.longitudeDegrees,
            target.latitudeDegrees,
            target.longitudeDegrees,
            results,
        )
        return results[0]
    }

    /**
     * Decides the next [ProximityState] with hysteresis to prevent flapping when the user lingers
     * near the boundary. The user must be within [radiusMeters] to be considered INSIDE, but must
     * move beyond `radiusMeters + exitBufferMeters` before flipping back to OUTSIDE. Once INSIDE,
     * distances in the buffer band hold the current state.
     *
     * @param current the previous state, used to apply the hysteresis band.
     * @param distanceMeters measured distance from the target center.
     * @param radiusMeters the target radius.
     * @param exitBufferMeters extra distance required to leave, on top of the radius.
     */
    fun evaluate(
        current: ProximityState,
        distanceMeters: Float,
        radiusMeters: Float,
        exitBufferMeters: Float,
    ): ProximityState = when {
        distanceMeters <= radiusMeters -> ProximityState.INSIDE
        distanceMeters > radiusMeters + exitBufferMeters -> ProximityState.OUTSIDE
        // In the hysteresis band: keep INSIDE if we were inside, otherwise treat as outside.
        current == ProximityState.INSIDE -> ProximityState.INSIDE
        else -> ProximityState.OUTSIDE
    }
}
