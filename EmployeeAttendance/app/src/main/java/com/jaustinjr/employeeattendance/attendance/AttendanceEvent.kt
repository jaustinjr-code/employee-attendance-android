package com.jaustinjr.employeeattendance.attendance

import kotlinx.serialization.Serializable

/** Whether an attendance event marks arriving at (clock-in) or leaving (clock-out) a worksite. */
enum class ClockType { CLOCK_IN, CLOCK_OUT }

/**
 * What triggered an attendance event: geofence-driven [AUTO] (arriving at / leaving a worksite) or
 * [MANUAL] (the user tapping the clock button on the attendance screen). This drives display rules:
 * a manual clock-out is shown on the attendance screen, an automatic one is not.
 */
enum class ClockSource { AUTO, MANUAL }

/**
 * A single recorded attendance event for a worksite. The append-only log of these is the local
 * source of truth for attendance; per-location clock-in/out values are derived from it.
 *
 * @param locationId the [com.jaustinjr.employeeattendance.location.registration.WorkLocation.id]
 *   this event belongs to.
 * @param type clock-in or clock-out.
 * @param epochMillis when the event occurred (ms since the Unix epoch).
 * @param source what triggered it; defaults to [ClockSource.AUTO] so events persisted before this
 *   field existed (all geofence-driven) decode correctly.
 */
@Serializable
data class AttendanceEvent(
    val locationId: String,
    val type: ClockType,
    val epochMillis: Long,
    val source: ClockSource = ClockSource.AUTO,
)
