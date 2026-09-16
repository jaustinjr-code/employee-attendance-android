package com.jaustinjr.employeeattendance.reporting

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPeriodTest {

    @Test
    fun `a week runs Sunday through Saturday`() {
        // 2026-09-10 is a Thursday.
        val week = ReportPeriod.containing(ReportPeriodType.WEEK, LocalDate.of(2026, 9, 10))

        assertEquals(LocalDate.of(2026, 9, 6), week.start)
        assertEquals(DayOfWeek.SUNDAY, week.start.dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 13), week.endExclusive)
        assertEquals(7, week.dayCount)
    }

    @Test
    fun `a Sunday starts its own week and a Saturday ends it`() {
        val sunday = LocalDate.of(2026, 9, 6)
        val saturday = LocalDate.of(2026, 9, 12)

        assertEquals(sunday, ReportPeriod.containing(ReportPeriodType.WEEK, sunday).start)
        assertEquals(sunday, ReportPeriod.containing(ReportPeriodType.WEEK, saturday).start)
    }

    @Test
    fun `a week can span two months`() {
        val week = ReportPeriod.containing(ReportPeriodType.WEEK, LocalDate.of(2026, 10, 1))

        assertEquals(LocalDate.of(2026, 9, 27), week.start)
        assertEquals(LocalDate.of(2026, 10, 4), week.endExclusive)
    }

    @Test
    fun `a month covers every day of the calendar month`() {
        val february = ReportPeriod.containing(ReportPeriodType.MONTH, LocalDate.of(2028, 2, 15))

        assertEquals(LocalDate.of(2028, 2, 1), february.start)
        assertEquals(LocalDate.of(2028, 3, 1), february.endExclusive)
        assertEquals(29, february.dayCount)
    }

    @Test
    fun `biweekly periods start on a Sunday and tile every fourteen days from the anchor`() {
        val anchor = ReportPeriod.BIWEEKLY_ANCHOR
        assertEquals(DayOfWeek.SUNDAY, anchor.dayOfWeek)

        var period = ReportPeriod.containing(ReportPeriodType.BIWEEKLY, anchor)
        assertEquals(anchor, period.start)
        repeat(100) {
            val next = period.next()
            assertEquals(period.endExclusive, next.start)
            assertEquals(14, next.dayCount)
            assertEquals(DayOfWeek.SUNDAY, next.start.dayOfWeek)
            period = next
        }
    }

    @Test
    fun `every day of a biweekly period maps to the same period`() {
        val period = ReportPeriod.containing(ReportPeriodType.BIWEEKLY, LocalDate.of(2026, 9, 10))

        period.days.forEach { day ->
            assertEquals(period, ReportPeriod.containing(ReportPeriodType.BIWEEKLY, day))
        }
    }

    @Test
    fun `biweekly pairing is stable before the anchor too`() {
        val early = ReportPeriod.containing(ReportPeriodType.BIWEEKLY, LocalDate.of(2020, 5, 5))

        assertEquals(DayOfWeek.SUNDAY, early.start.dayOfWeek)
        assertTrue(LocalDate.of(2020, 5, 5) in early)
        assertEquals(early, early.next().previous())
    }

    @Test
    fun `the last completed biweekly period ends on or before today`() {
        val today = LocalDate.of(2026, 9, 10)
        val last = ReportPeriod.lastCompletedBiweekly(today)
        val current = ReportPeriod.containing(ReportPeriodType.BIWEEKLY, today)

        assertEquals(current.start, last.endExclusive)
        assertFalse(today in last)
    }

    @Test
    fun `previous and next move one aligned period`() {
        val month = ReportPeriod.containing(ReportPeriodType.MONTH, LocalDate.of(2026, 1, 20))

        assertEquals(LocalDate.of(2025, 12, 1), month.previous().start)
        assertEquals(LocalDate.of(2026, 2, 1), month.next().start)
    }
}
