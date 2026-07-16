package com.jaustinjr.employeeattendance.location.proximity

import com.jaustinjr.employeeattendance.location.tracking.LocationSample

/**
 * Seam for feeding proximity decisions, so the coordinator that drives it can be unit-tested without
 * the android.location.Location distance math in [ProximityRepository]. Implemented by
 * [ProximityRepository].
 */
interface ProximityUpdater {
    /** Feed a foreground fix; the implementation computes and commits the transition. */
    fun onLocation(sample: LocationSample, target: GeofenceTarget)

    /** Clear proximity (e.g. no target registered). */
    fun reset()
}
