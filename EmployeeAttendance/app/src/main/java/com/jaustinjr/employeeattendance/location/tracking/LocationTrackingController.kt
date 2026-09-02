package com.jaustinjr.employeeattendance.location.tracking

import android.util.Log
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState

/**
 * Decides *how* location tracking should run for a given permission level and starts/stops the
 * background service accordingly. This is the single place that encodes the graceful-degradation
 * policy, so callers don't have to reason about permission tiers.
 *
 * - [LocationAccessLevel.ALWAYS] -> start the [LocationTrackingService] for full background
 *   tracking. If the platform refuses the start (see [TrackingServiceLauncher.start]), degrade to
 *   [TrackingStatus.FOREGROUND_ONLY] rather than reporting tracking that is not running.
 * - [LocationAccessLevel.WHEN_IN_USE] -> do not start a background service (it would be killed when
 *   the app leaves the foreground); report [TrackingStatus.FOREGROUND_ONLY] so the UI can drive
 *   foreground-only updates via [LocationTracker] and show the degraded-mode notice.
 * - [LocationAccessLevel.NONE] -> tracking is off.
 */
class LocationTrackingController(
    private val serviceLauncher: TrackingServiceLauncher,
    private val locationState: LocationStateRepository,
) {

    /**
     * Reconciles the running tracking mode with [permission]. Safe to call repeatedly (e.g. on each
     * permission refresh); it only acts on the transitions that matter.
     */
    fun sync(permission: LocationPermissionState) {
        Log.d(TAG, "sync: access=${permission.accessLevel}")
        when (permission.accessLevel) {
            LocationAccessLevel.ALWAYS -> {
                if (!serviceLauncher.start()) {
                    // Permission allows background tracking but the platform would not let the
                    // service start. The foreground collector owned by the UI layer still supplies
                    // fixes while a screen is visible, so report the degraded mode the UI already
                    // knows how to show instead of claiming background tracking is active.
                    Log.w(TAG, "sync: background start refused; degrading to foreground-only")
                    locationState.updateStatus(TrackingStatus.FOREGROUND_ONLY)
                }
            }

            LocationAccessLevel.WHEN_IN_USE -> {
                // No background service; a foreground collector (owned by the UI layer) supplies
                // fixes while the app is visible.
                serviceLauncher.stop()
                locationState.updateStatus(TrackingStatus.FOREGROUND_ONLY)
            }

            LocationAccessLevel.NONE -> {
                serviceLauncher.stop()
                locationState.updateStatus(TrackingStatus.STOPPED)
            }
        }
    }

    /** Stops all tracking regardless of permission (e.g. a user-facing "pause tracking" action). */
    fun stop() {
        Log.d(TAG, "stop")
        serviceLauncher.stop()
        locationState.updateStatus(TrackingStatus.STOPPED)
    }

    private companion object {
        const val TAG = "TrackCtl"
    }
}
