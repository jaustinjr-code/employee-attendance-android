package com.jaustinjr.employeeattendance.location.proximity

/** Whether the user is currently within a watched [GeofenceTarget]. */
enum class ProximityState {
    /** No fix / no target yet, so proximity is undetermined. */
    UNKNOWN,

    /** The user is within the target's radius. */
    INSIDE,

    /** The user is outside the target's radius. */
    OUTSIDE,
}

/**
 * A transition across a work-location boundary. This is the integration seam for attendance: an
 * [Arrived] event is where auto clock-in would fire, and [Departed] where auto clock-out would.
 * Kept as a plain event so the (future) shift/attendance system can consume it without this layer
 * depending on it.
 */
sealed interface ProximityEvent {
    val targetId: String

    /** The user entered the region for [targetId] (arrived at the work location). */
    data class Arrived(override val targetId: String) : ProximityEvent

    /** The user left the region for [targetId] (departed the work location). */
    data class Departed(override val targetId: String) : ProximityEvent
}
