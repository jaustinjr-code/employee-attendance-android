package com.jaustinjr.employeeattendance.reporting

import android.content.res.Resources
import com.jaustinjr.employeeattendance.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The user-visible text a report is rendered with, as `String.format` templates.
 *
 * A plain value rather than `Resources` so [ReportTextFormatter] runs on the JVM; production code
 * builds it with [fromResources] at the moment of formatting, so a locale change is never stale.
 */
data class ReportStrings(
    val title: String,
    val periodWeek: String,
    val periodBiweekly: String,
    val periodMonth: String,
    /** %1$s start date, %2$s end date. */
    val dateRange: String,
    /** %1$d hours, %2$d minutes. */
    val duration: String,
    /** %1$s worksite, %2$s start time. */
    val activeShiftNote: String,
    val generalTimeclock: String,
    val removedWorksite: String,
    val statTotal: String,
    val statShifts: String,
    val statDaysWorked: String,
    val statAveragePerDay: String,
    val statLongestShift: String,
    val statAverageClockIn: String,
    val sectionByDay: String,
    val sectionByWorksite: String,
    val noShifts: String,
    /** %1$s period label, %2$s date range. */
    val subject: String,
    /** %1$s total, %2$s the [shiftCount] phrase. */
    val biweeklyHeadline: String,
    /** "1 shift" / "3 shifts", pluralized for the locale. */
    val shiftCount: (Int) -> String,
    /** %1$s duration. */
    val changeUp: String,
    /** %1$s duration. */
    val changeDown: String,
    val changeNone: String,
) {
    companion object {
        fun fromResources(resources: Resources): ReportStrings = with(resources) {
            ReportStrings(
                title = getString(R.string.report_text_title),
                periodWeek = getString(R.string.reports_period_week),
                periodBiweekly = getString(R.string.reports_period_biweekly),
                periodMonth = getString(R.string.reports_period_month),
                dateRange = getString(R.string.reports_date_range),
                duration = getString(R.string.reports_duration),
                activeShiftNote = getString(R.string.reports_active_shift_note),
                generalTimeclock = getString(R.string.reports_worksite_general),
                removedWorksite = getString(R.string.reports_worksite_removed),
                statTotal = getString(R.string.reports_stat_total),
                statShifts = getString(R.string.reports_stat_shifts),
                statDaysWorked = getString(R.string.reports_stat_days_worked),
                statAveragePerDay = getString(R.string.reports_stat_average_per_day),
                statLongestShift = getString(R.string.reports_stat_longest_shift),
                statAverageClockIn = getString(R.string.reports_stat_average_clock_in),
                sectionByDay = getString(R.string.report_text_by_day),
                sectionByWorksite = getString(R.string.report_text_by_worksite),
                noShifts = getString(R.string.reports_empty),
                subject = getString(R.string.report_share_subject),
                biweeklyHeadline = getString(R.string.reports_biweekly_headline),
                shiftCount = { count -> getQuantityString(R.plurals.reports_shift_count, count, count) },
                changeUp = getString(R.string.reports_change_up),
                changeDown = getString(R.string.reports_change_down),
                changeNone = getString(R.string.reports_change_none),
            )
        }
    }
}

/** Renders reports as plain text for sharing and notifications. */
class ReportTextFormatter(
    private val strings: ReportStrings,
    private val locale: Locale,
    private val zone: ZoneId,
) {
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val dayFormat = DateTimeFormatter.ofPattern("EEE", locale)
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    fun duration(millis: Long): String {
        val totalMinutes = abs(millis) / 60_000
        return strings.duration.format(locale, totalMinutes / 60, totalMinutes % 60)
    }

    fun periodLabel(type: ReportPeriodType): String = when (type) {
        ReportPeriodType.WEEK -> strings.periodWeek
        ReportPeriodType.BIWEEKLY -> strings.periodBiweekly
        ReportPeriodType.MONTH -> strings.periodMonth
    }

    /** Inclusive range, so a Sunday-start week reads "Sep 6 – Sep 12", not "– Sep 13". */
    fun dateRange(period: ReportPeriod): String = strings.dateRange.format(
        locale,
        dateFormat.format(period.start),
        dateFormat.format(period.endExclusive.minusDays(1)),
    )

    fun worksite(label: WorksiteLabel): String = when (label) {
        is WorksiteLabel.Registered -> label.name
        WorksiteLabel.GeneralTimeclock -> strings.generalTimeclock
        WorksiteLabel.Removed -> strings.removedWorksite
    }

    /** The start carries its weekday: a shift open since last night must not read as this evening. */
    fun activeShiftNote(notice: ActiveShiftNotice): String {
        val start = notice.startedAt.atZone(zone)
        return strings.activeShiftNote.format(
            locale,
            worksite(notice.label),
            "${dayFormat.format(start)} ${timeFormat.format(start)}",
        )
    }

    fun shiftCount(count: Int): String = strings.shiftCount(count)

    fun timeOfDay(time: LocalTime): String = timeFormat.format(time)

    fun subject(period: ReportPeriod): String =
        strings.subject.format(locale, periodLabel(period.type), dateRange(period))

    fun change(summary: BiweeklySummary): String = when {
        summary.changeMillis >= MINUTE -> strings.changeUp.format(locale, duration(summary.changeMillis))
        summary.changeMillis <= -MINUTE -> strings.changeDown.format(locale, duration(summary.changeMillis))
        else -> strings.changeNone
    }

    fun biweeklyHeadline(summary: BiweeklySummary): String = strings.biweeklyHeadline.format(
        locale,
        duration(summary.report.totalMillis),
        strings.shiftCount(summary.report.shiftCount),
    )

    fun fullReport(report: AttendanceReport, activeShift: ActiveShiftNotice?): String = buildString {
        appendLine(strings.title)
        appendLine("${periodLabel(report.period.type)}: ${dateRange(report.period)}")
        if (activeShift != null) {
            appendLine()
            appendLine(activeShiftNote(activeShift))
        }
        appendLine()
        if (report.isEmpty) {
            appendLine(strings.noShifts)
            return@buildString
        }
        appendLine("${strings.statTotal}: ${duration(report.totalMillis)}")
        appendLine("${strings.statShifts}: ${report.shiftCount}")
        appendLine("${strings.statDaysWorked}: ${report.daysWorked}")
        appendLine("${strings.statAveragePerDay}: ${duration(report.averageMillisPerWorkedDay)}")
        appendLine("${strings.statLongestShift}: ${duration(report.longestShiftMillis)}")
        report.averageClockIn?.let {
            appendLine("${strings.statAverageClockIn}: ${timeOfDay(it)}")
        }
        appendLine()
        appendLine(strings.sectionByDay)
        report.days.forEach { day ->
            appendLine("${dayLabel(day.date)}: ${duration(day.workedMillis)}")
        }
        appendLine()
        appendLine(strings.sectionByWorksite)
        report.worksites.forEach { total ->
            val percent = if (report.totalMillis == 0L) 0 else (total.workedMillis * 100 / report.totalMillis)
            appendLine("${worksite(total.label)}: ${duration(total.workedMillis)} ($percent%)")
        }
    }

    private fun dayLabel(date: LocalDate): String = "${dayFormat.format(date)}, ${dateFormat.format(date)}"

    private companion object {
        const val MINUTE = 60_000L
    }
}
