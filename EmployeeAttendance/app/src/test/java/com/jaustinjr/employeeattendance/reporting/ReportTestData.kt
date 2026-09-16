package com.jaustinjr.employeeattendance.reporting

import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import java.time.LocalDateTime
import java.time.ZoneId

/** Has a DST change: clocks go forward 2026-03-08 and back 2026-11-01. */
val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

const val HOUR = 3_600_000L
const val MINUTE = 60_000L

fun millisAt(text: String, zone: ZoneId = NEW_YORK): Long =
    LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

fun clockIn(locationId: String, at: String, zone: ZoneId = NEW_YORK) =
    AttendanceEvent(locationId, ClockType.CLOCK_IN, millisAt(at, zone), ClockSource.MANUAL)

fun clockOut(locationId: String, at: String, zone: ZoneId = NEW_YORK) =
    AttendanceEvent(locationId, ClockType.CLOCK_OUT, millisAt(at, zone), ClockSource.MANUAL)

fun worksite(id: String, name: String) = WorkLocation(
    id = id,
    name = name,
    latitudeDegrees = 40.0,
    longitudeDegrees = -74.0,
    radiusMeters = 100f,
)

val ENGLISH_STRINGS = ReportStrings(
    title = "Employee Attendance report",
    periodWeek = "Week",
    periodBiweekly = "Two weeks",
    periodMonth = "Month",
    dateRange = "%1\$s – %2\$s",
    duration = "%1\$dh %2\$02dm",
    activeShiftNote = "You're clocked in at %1\$s since %2\$s. That shift isn't included until you clock out.",
    generalTimeclock = "General timeclock",
    removedWorksite = "Removed worksite",
    statTotal = "Total worked",
    statShifts = "Shifts",
    statDaysWorked = "Days worked",
    statAveragePerDay = "Average per day worked",
    statLongestShift = "Longest shift",
    statAverageClockIn = "Average clock-in",
    sectionByDay = "By day",
    sectionByWorksite = "By worksite",
    noShifts = "No completed shifts in this period.",
    subject = "Attendance report: %1\$s, %2\$s",
    biweeklyHeadline = "%1\$s across %2\$s",
    shiftCount = { if (it == 1) "1 shift" else "$it shifts" },
    changeUp = "Up %1\$s from the two weeks before",
    changeDown = "Down %1\$s from the two weeks before",
    changeNone = "Same as the two weeks before",
)
