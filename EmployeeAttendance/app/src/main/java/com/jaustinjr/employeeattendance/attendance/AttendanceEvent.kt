package com.jaustinjr.employeeattendance.attendance

import kotlinx.serialization.Serializable

/** Whether an attendance event marks arriving at (clock-in) or leaving (clock-out) a worksite. */
enum class ClockType { CLOCK_IN, CLOCK_OUT }

/**
 * A single recorded attendance event for a worksite. The append-only log of these is the local
 * source of truth for attendance; per-location "last clock-in" values are derived from it.
 *
 * @param locationId the [com.jaustinjr.employeeattendance.location.registration.WorkLocation.id]
 *   this event belongs to.
 * @param type clock-in or clock-out.
 * @param epochMillis when the event occurred (ms since the Unix epoch).
 */
@Serializable
data class AttendanceEvent(
    val locationId: String,
    val type: ClockType,
    val epochMillis: Long,
)
