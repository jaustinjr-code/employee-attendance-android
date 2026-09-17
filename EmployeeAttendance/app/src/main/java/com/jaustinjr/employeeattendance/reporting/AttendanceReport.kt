package com.jaustinjr.employeeattendance.reporting

import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Time worked on one calendar day. */
data class DayTotal(val date: LocalDate, val workedMillis: Long)

/** How a report names the worksite a total belongs to. Resolved to a string only at display time. */
sealed interface WorksiteLabel {
    data class Registered(val name: String) : WorksiteLabel

    /** Manual clock in/out with no worksite, see [AttendanceRepository.GENERAL_TIMECLOCK_ID]. */
    data object GeneralTimeclock : WorksiteLabel

    /** Attendance recorded against a worksite that has since been deleted. */
    data object Removed : WorksiteLabel
}

data class WorksiteTotal(
    val locationId: String,
    val label: WorksiteLabel,
    val workedMillis: Long,
)

/**
 * Analysis of the completed shifts that overlap [period].
 *
 * Shifts are clipped to the period and split at midnight, so a shift from 22:00 Saturday to
 * 06:00 Sunday credits two hours to Saturday and six to Sunday, and only the part inside the
 * period counts toward it. Shifts still in progress are excluded entirely.
 *
 * @param days one entry per day of the period, including days with no work.
 * @param worksites one entry per worksite with time in the period, most time first.
 * @param averageClockIn mean clock-in time of day across shifts that started in the period.
 */
data class AttendanceReport(
    val period: ReportPeriod,
    val totalMillis: Long,
    val days: List<DayTotal>,
    val worksites: List<WorksiteTotal>,
    val shiftCount: Int,
    val daysWorked: Int,
    val averageMillisPerWorkedDay: Long,
    val longestShiftMillis: Long,
    val averageClockIn: LocalTime?,
) {
    val isEmpty: Boolean get() = shiftCount == 0
}

/** The user is clocked in right now, so the report is missing the shift in progress. */
data class ActiveShiftNotice(val label: WorksiteLabel, val startedAt: Instant)

/** The most recent completed biweekly period, compared with the one before it. */
data class BiweeklySummary(
    val report: AttendanceReport,
    val previousTotalMillis: Long,
) {
    val changeMillis: Long get() = report.totalMillis - previousTotalMillis
    val topWorksite: WorksiteTotal? get() = report.worksites.firstOrNull()
}

/** Pure computation of an [AttendanceReport]; see [ReportGenerator] for the cached entry point. */
object ReportCalculator {

    fun calculate(
        period: ReportPeriod,
        sessions: List<WorkSession>,
        worksites: List<WorkLocation>,
        zone: ZoneId,
    ): AttendanceReport {
        val periodStart = period.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val periodEnd = period.endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val overlapping = sessions.filter { it.endMillis > periodStart && it.startMillis < periodEnd }

        val perDay = HashMap<LocalDate, Long>()
        val perLocation = LinkedHashMap<String, Long>()
        overlapping.forEach { session ->
            val clippedStart = maxOf(session.startMillis, periodStart)
            val clippedEnd = minOf(session.endMillis, periodEnd)
            perLocation.merge(session.locationId, clippedEnd - clippedStart, Long::plus)
            var cursor = clippedStart
            while (cursor < clippedEnd) {
                val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                // atStartOfDay rather than +24h: days are 23 or 25 hours long across DST changes.
                val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val segmentEnd = minOf(nextMidnight, clippedEnd)
                perDay.merge(date, segmentEnd - cursor, Long::plus)
                cursor = segmentEnd
            }
        }

        val days = period.days.map { DayTotal(it, perDay[it] ?: 0L) }
        val total = perDay.values.sum()
        val daysWorked = days.count { it.workedMillis > 0 }
        val namesById = worksites.associate { it.id to it.name }
        val worksiteTotals = perLocation
            .map { (id, millis) -> WorksiteTotal(id, labelFor(id, namesById), millis) }
            .sortedByDescending { it.workedMillis }

        val startedInPeriod = overlapping.filter { it.startMillis >= periodStart }
        val averageClockIn = if (startedInPeriod.isEmpty()) {
            null
        } else {
            val meanSecondOfDay = startedInPeriod
                .map { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalTime().toSecondOfDay() }
                .average()
            LocalTime.ofSecondOfDay(meanSecondOfDay.toLong())
        }

        return AttendanceReport(
            period = period,
            totalMillis = total,
            days = days,
            worksites = worksiteTotals,
            shiftCount = overlapping.size,
            daysWorked = daysWorked,
            averageMillisPerWorkedDay = if (daysWorked == 0) 0L else total / daysWorked,
            longestShiftMillis = overlapping.maxOfOrNull { it.durationMillis } ?: 0L,
            averageClockIn = averageClockIn,
        )
    }

    fun labelFor(locationId: String, namesById: Map<String, String>): WorksiteLabel =
        when {
            locationId == AttendanceRepository.GENERAL_TIMECLOCK_ID -> WorksiteLabel.GeneralTimeclock
            else -> namesById[locationId]?.let { WorksiteLabel.Registered(it) } ?: WorksiteLabel.Removed
        }
}
