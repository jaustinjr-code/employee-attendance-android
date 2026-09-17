package com.jaustinjr.employeeattendance.reporting

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** The length of a reporting window. Every window starts on a Sunday except [MONTH]. */
enum class ReportPeriodType { WEEK, BIWEEKLY, MONTH }

/**
 * A half-open range of calendar days, `[start, endExclusive)`, in the device's time zone.
 *
 * Periods are always aligned to their type — build them with [containing] rather than the
 * constructor — so two periods of the same type either coincide or do not overlap, which is what
 * makes them usable as cache keys.
 */
data class ReportPeriod(
    val type: ReportPeriodType,
    val start: LocalDate,
    val endExclusive: LocalDate,
) {
    val dayCount: Int get() = ChronoUnit.DAYS.between(start, endExclusive).toInt()

    /** Each day in the period, in order. */
    val days: List<LocalDate> get() = (0 until dayCount).map { start.plusDays(it.toLong()) }

    operator fun contains(date: LocalDate): Boolean = date >= start && date < endExclusive

    fun previous(): ReportPeriod = containing(type, start.minusDays(1))

    fun next(): ReportPeriod = containing(type, endExclusive)

    companion object {
        /**
         * Biweekly windows repeat every 14 days from this Sunday. A fixed anchor keeps the pairs
         * stable forever: they never shift with when the user opted in or reinstalled.
         */
        val BIWEEKLY_ANCHOR: LocalDate = LocalDate.of(2023, 1, 1)

        fun containing(type: ReportPeriodType, date: LocalDate): ReportPeriod = when (type) {
            ReportPeriodType.WEEK -> {
                val start = startOfWeek(date)
                ReportPeriod(type, start, start.plusWeeks(1))
            }
            ReportPeriodType.BIWEEKLY -> {
                val weekStart = startOfWeek(date)
                val offset = Math.floorMod(
                    ChronoUnit.DAYS.between(BIWEEKLY_ANCHOR, weekStart),
                    BIWEEKLY_DAYS,
                )
                val start = weekStart.minusDays(offset)
                ReportPeriod(type, start, start.plusDays(BIWEEKLY_DAYS))
            }
            ReportPeriodType.MONTH -> {
                val start = date.withDayOfMonth(1)
                ReportPeriod(type, start, start.plusMonths(1))
            }
        }

        /** The most recent biweekly period that has fully ended before [today]. */
        fun lastCompletedBiweekly(today: LocalDate): ReportPeriod =
            containing(ReportPeriodType.BIWEEKLY, today).previous()

        private fun startOfWeek(date: LocalDate): LocalDate =
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

        private const val BIWEEKLY_DAYS = 14L
    }
}
