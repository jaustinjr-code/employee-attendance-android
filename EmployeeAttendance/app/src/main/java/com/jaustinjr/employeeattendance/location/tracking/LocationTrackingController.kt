package com.jaustinjr.employeeattendance.location.tracking

import android.content.Context
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState

/**
 * Decides *how* location tracking should run for a given permission level and starts/stops the
 * background service accordingly. This is the single place that encodes the graceful-degradation
 * policy, so callers don't have to reason about permission tiers.
 *
 * - [LocationAccessLevel.ALWAYS] -> start the [LocationTrackingService] for full background
 *   tracking.
 * - [LocationAccessLevel.WHEN_IN_USE] -> do not start a background service (it would be killed when
 *   the app leaves the foreground); report [TrackingStatus.FOREGROUND_ONLY] so the UI can drive
 *   foreground-only updates via [LocationTracker] and show the degraded-mode notice.
 * - [LocationAccessLevel.NONE] -> tracking is off.
 */
class LocationTrackingController(
    private val appContext: Context,
    private val locationState: LocationStateRepository,
) {

    /**
     * Reconciles the running tracking mode with [permission]. Safe to call repeatedly (e.g. on each
     * permission refresh); it only acts on the transitions that matter.
     */
    fun sync(permission: LocationPermissionState) {
        when (permission.accessLevel) {
            LocationAccessLevel.ALWAYS -> {
                LocationTrackingService.start(appContext)
            }

            LocationAccessLevel.WHEN_IN_USE -> {
                // No background service; a foreground collector (owned by the UI layer) supplies
                // fixes while the app is visible.
                LocationTrackingService.stop(appContext)
                locationState.updateStatus(TrackingStatus.FOREGROUND_ONLY)
            }

            LocationAccessLevel.NONE -> {
                LocationTrackingService.stop(appContext)
                locationState.updateStatus(TrackingStatus.STOPPED)
            }
        }
    }

    /** Stops all tracking regardless of permission (e.g. a user-facing "pause tracking" action). */
    fun stop() {
        LocationTrackingService.stop(appContext)
        locationState.updateStatus(TrackingStatus.STOPPED)
    }
}
