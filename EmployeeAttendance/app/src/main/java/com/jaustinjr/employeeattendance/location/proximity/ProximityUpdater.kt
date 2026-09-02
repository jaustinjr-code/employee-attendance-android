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

    /**
     * Erases all proximity data — the state *and* the remembered target id, in memory and on disk —
     * without emitting any [ProximityEvent].
     *
     * This is the "delete all data" seam, distinct from [reset]: [reset] means "stop tracking, you
     * just left", so it still emits a Departed when the user was INSIDE. [clear] means "this data
     * must not exist anymore", so it emits nothing — the worksite it would name has already been
     * deleted, and attendance history is being wiped alongside it.
     *
     * @param deletedTargetIds ids of the targets being deleted alongside this erasure. Their OS
     *   geofences are torn down asynchronously, so a straggling transition can still arrive after
     *   this call; implementations must ignore those rather than let them re-persist a deleted id.
     *
     *   **Precondition: every id passed here — and whatever the implementation is currently tracking
     *   — must belong to a worksite that is actually being deleted.** Implementations may ignore
     *   these ids for the remainder of the process, so naming a still-live worksite would silently
     *   stop tracking it until the app is restarted. Use [reset], not this, to stop tracking a
     *   worksite that continues to exist.
     */
    fun clear(deletedTargetIds: Set<String> = emptySet())
}
