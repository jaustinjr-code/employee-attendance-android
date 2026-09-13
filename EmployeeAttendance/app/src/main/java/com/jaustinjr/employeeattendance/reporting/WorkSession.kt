package com.jaustinjr.employeeattendance.reporting

import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.attendance.ClockType

/** A completed shift: a clock-in matched with the clock-out that closed it. */
data class WorkSession(
    val locationId: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = endMillis - startMillis
}

/** A clock-in with no clock-out yet. Never counted in a report; only surfaced as a notice. */
data class ActiveShift(val locationId: String, val startMillis: Long)

/** The event log folded into shifts. */
data class SessionLog(
    val completed: List<WorkSession>,
    val active: List<ActiveShift>,
) {
    companion object {
        /**
         * Pairs clock-ins with clock-outs per worksite, in time order.
         *
         * The log is not guaranteed to alternate: a manual clock-in can be recorded on top of an
         * automatic one, and a clock-out can outlive an undone clock-in. So a clock-in while a shift
         * is already open is ignored (the shift keeps its earlier start), and a clock-out with no
         * open shift is ignored. Zero-length shifts carry no time and are dropped.
         */
        fun from(events: List<AttendanceEvent>): SessionLog {
            val completed = mutableListOf<WorkSession>()
            val active = mutableListOf<ActiveShift>()
            events.groupBy { it.locationId }.forEach { (locationId, forLocation) ->
                var openStart: Long? = null
                forLocation.sortedBy { it.epochMillis }.forEach { event ->
                    when (event.type) {
                        ClockType.CLOCK_IN -> if (openStart == null) openStart = event.epochMillis
                        ClockType.CLOCK_OUT -> {
                            val start = openStart
                            if (start != null && event.epochMillis > start) {
                                completed += WorkSession(locationId, start, event.epochMillis)
                            }
                            openStart = null
                        }
                    }
                }
                openStart?.let { active += ActiveShift(locationId, it) }
            }
            return SessionLog(
                completed = completed.sortedBy { it.startMillis },
                active = active.sortedBy { it.startMillis },
            )
        }
    }
}
