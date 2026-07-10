package com.jaustinjr.employeeattendance.location.permission

import android.Manifest
import android.os.Build

/**
 * The level of location access the app currently holds.
 *
 * The distinction between [WHEN_IN_USE] and [ALWAYS] only exists on Android 10 (API 29) and
 * above, where background location access ([Manifest.permission.ACCESS_BACKGROUND_LOCATION]) is a
 * separate, incremental grant. On older versions any foreground location grant also permits
 * background access, so a foreground grant is reported as [ALWAYS].
 */
enum class LocationAccessLevel {
    /** No location permission has been granted. The location feature cannot function. */
    NONE,

    /**
     * Location is only available while the app is in the foreground ("While Using App").
     *
     * Background geofencing and proximity tracking are unavailable or unreliable, so the location
     * feature runs in a degraded mode. The user should be told about this trade-off.
     */
    WHEN_IN_USE,

    /**
     * Location is available even while the app is backgrounded ("Allow all the time"). This is the
     * ideal state for reliable proximity detection and auto clock-in.
     */
    ALWAYS;

    /** Whether any location access at all has been granted. */
    val isGranted: Boolean
        get() = this != NONE

    /**
     * Whether background tracking (geofencing / proximity while backgrounded) can run reliably.
     * Only [ALWAYS] supports the full, non-degraded experience.
     */
    val supportsBackgroundTracking: Boolean
        get() = this == ALWAYS

    /**
     * Whether the location feature is usable but degraded — access is granted but limited to the
     * foreground. Callers use this to surface the "reduced experience" messaging.
     */
    val isDegraded: Boolean
        get() = this == WHEN_IN_USE
}

/**
 * A snapshot of the app's current location permission state.
 *
 * @param accessLevel how far the granted permissions reach (see [LocationAccessLevel]).
 * @param isPrecise whether precise (fine) location is granted. When false, only approximate
 *   (coarse) location is available, which reduces geofence accuracy. Relevant on Android 12+ where
 *   the user can grant approximate-only location.
 */
data class LocationPermissionState(
    val accessLevel: LocationAccessLevel = LocationAccessLevel.NONE,
    val isPrecise: Boolean = false,
) {
    val isGranted: Boolean get() = accessLevel.isGranted
    val supportsBackgroundTracking: Boolean get() = accessLevel.supportsBackgroundTracking
    val isDegraded: Boolean get() = accessLevel.isDegraded

    companion object {
        val Denied = LocationPermissionState(LocationAccessLevel.NONE, isPrecise = false)
    }
}

/**
 * Central definition of the Android runtime permissions the location feature depends on, plus the
 * platform rules for how they must be requested. Keeping this in one place avoids scattering
 * version checks and permission strings across the codebase.
 */
object LocationPermissions {
    const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
    const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
    const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    /**
     * The foreground permissions requested together in the first prompt. Both are requested so the
     * system can offer the user precise/approximate choice on Android 12+.
     */
    val foreground: Array<String> = arrayOf(FINE, COARSE)

    /**
     * Whether background location is a distinct grant on this device. Below API 29 a foreground
     * grant already covers background use, so no separate request is needed.
     */
    val backgroundPermissionExists: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Android 11+ requires background location to be requested on its own, in a second step, after
     * foreground access has already been granted. It cannot be bundled with the foreground request.
     */
    val backgroundMustBeRequestedSeparately: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
