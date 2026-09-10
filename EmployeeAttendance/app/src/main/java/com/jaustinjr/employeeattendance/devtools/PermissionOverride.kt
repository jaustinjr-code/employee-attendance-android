package com.jaustinjr.employeeattendance.devtools

import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState

/**
 * A developer-chosen stand-in for the system location permission grant, so every branch of the
 * permission-driven UI (setup chip, degraded notice, approximate notice, map gating) can be seen
 * without actually revoking permissions in system settings and restarting the app.
 *
 * [OFF] means "use the real grant" — the only value a non-developer build ever sees. The remaining
 * values enumerate the states the app distinguishes; [LocationPermissionState] carries an access
 * level *and* a precision flag, so the granted levels appear once per precision.
 *
 * Persisted by name, so adding a value is safe but renaming one silently falls back to [OFF].
 */
enum class PermissionOverride {
    /** No override; the real [com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository] wins. */
    OFF,

    /** Pretend location is denied outright: the setup chip and rationale flow. */
    DENIED,

    /** "While using the app" + approximate: degraded *and* imprecise, the worst usable state. */
    WHEN_IN_USE_APPROXIMATE,

    /** "While using the app" + precise: foreground-only tracking, no geofences. */
    WHEN_IN_USE_PRECISE,

    /** "Allow all the time" + approximate: geofences register but fire unreliably. */
    ALWAYS_APPROXIMATE,

    /** "Allow all the time" + precise: the fully-supported happy path. */
    ALWAYS_PRECISE,
    ;

    /**
     * The state this override forces, or null for [OFF] (meaning: defer to the real grant).
     * Callers pick `toState() ?: realState` rather than branching on the enum.
     */
    fun toState(): LocationPermissionState? = when (this) {
        OFF -> null
        DENIED -> LocationPermissionState.Denied
        WHEN_IN_USE_APPROXIMATE ->
            LocationPermissionState(LocationAccessLevel.WHEN_IN_USE, isPrecise = false)
        WHEN_IN_USE_PRECISE ->
            LocationPermissionState(LocationAccessLevel.WHEN_IN_USE, isPrecise = true)
        ALWAYS_APPROXIMATE ->
            LocationPermissionState(LocationAccessLevel.ALWAYS, isPrecise = false)
        ALWAYS_PRECISE ->
            LocationPermissionState(LocationAccessLevel.ALWAYS, isPrecise = true)
    }

    companion object {
        /** Parses a persisted name, degrading to [OFF] for anything unrecognised. */
        fun fromName(name: String?): PermissionOverride =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: OFF
    }
}
