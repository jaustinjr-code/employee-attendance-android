package com.jaustinjr.employeeattendance.attendance

import kotlinx.serialization.Serializable

/** Whether an attendance event marks arriving at (clock-in) or leaving (clock-out) a worksite. */
enum class ClockType { CLOCK_IN, CLOCK_OUT }

/**
 * What triggered an attendance event: geofence-driven [AUTO] (arriving at / leaving a worksite),
 * [MANUAL] (the user tapping the clock button on the attendance screen), or [SIMULATED] (written
 * directly by the debug-only developer tools).
 *
 * Display rules branch on [MANUAL] only — a manual clock-out is shown on the attendance screen, an
 * automatic one is not — so [SIMULATED] deliberately falls in the same "not manual" bucket as [AUTO]
 * and changes no display behaviour. Adding a value here means auditing every branch on this enum:
 * as of this writing the only one in production is `lastClockOutManual` in
 * [DefaultAttendanceRepository.attendance]'s derivation.
 */
enum class ClockSource {
    /** A real geofence transition drove this event through the auto-clock pipeline. */
    AUTO,

    /** The user tapped the clock button. */
    MANUAL,

    /**
     * Written directly by
     * [com.jaustinjr.employeeattendance.devtools.facade.DevAttendanceFacade], so developer-created
     * attendance is distinguishable from the user's own and can be cleared on its own.
     *
     * Note this tags *direct* developer writes only. A developer *simulating a geofence arrival*
     * drives the production pipeline on purpose, and the event it records is `AUTO` — indistinguishable
     * from a genuine one by design. See `DevAttendanceFacade`'s KDoc.
     */
    SIMULATED,
}

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
