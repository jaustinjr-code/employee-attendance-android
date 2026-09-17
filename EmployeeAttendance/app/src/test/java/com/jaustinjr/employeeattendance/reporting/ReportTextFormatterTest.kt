package com.jaustinjr.employeeattendance.reporting

import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTextFormatterTest {

    private val formatter = ReportTextFormatter(ENGLISH_STRINGS, Locale.US, NEW_YORK)
    private val week = ReportPeriod.containing(ReportPeriodType.WEEK, LocalDate.of(2026, 9, 10))
    private val worksites = listOf(worksite("a", "Office"))

    /** Recent CLDR data separates "9:00" and "AM" with a narrow no-break space. */
    private fun String.normalizeSpaces() = replace('\u202F', ' ').replace('\u00A0', ' ')

    private fun report(vararg events: com.jaustinjr.employeeattendance.attendance.AttendanceEvent) =
        ReportGenerator().report(week, events.toList(), worksites, NEW_YORK)

    @Test
    fun `durations are hours and zero-padded minutes`() {
        assertEquals("0h 00m", formatter.duration(0))
        assertEquals("7h 05m", formatter.duration(7 * HOUR + 5 * MINUTE + 59_000))
        assertEquals("26h 30m", formatter.duration(26 * HOUR + 30 * MINUTE))
    }

    @Test
    fun `times of day follow the formatter's locale, not the JVM default`() {
        val german = ReportTextFormatter(ENGLISH_STRINGS, Locale.GERMANY, NEW_YORK)
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("14:05", german.timeOfDay(java.time.LocalTime.of(14, 5)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the date range is inclusive of the last day`() {
        assertEquals("Sep 6, 2026 – Sep 12, 2026", formatter.dateRange(week))
    }

    @Test
    fun `the full report lists totals, every day and every worksite`() {
        val text = formatter.fullReport(
            report(clockIn("a", "2026-09-08T09:00"), clockOut("a", "2026-09-08T17:30")),
            activeShift = null,
        ).normalizeSpaces()

        assertTrue(text, text.startsWith("Employee Attendance report\nWeek: Sep 6, 2026 – Sep 12, 2026\n"))
        assertTrue(text, "Total worked: 8h 30m" in text)
        assertTrue(text, "Shifts: 1" in text)
        assertTrue(text, "Average clock-in: 9:00 AM" in text)
        assertTrue(text, "Tue, Sep 8, 2026: 8h 30m" in text)
        assertTrue(text, "Sat, Sep 12, 2026: 0h 00m" in text)
        assertTrue(text, "Office: 8h 30m (100%)" in text)
        assertFalse(text, "clocked in" in text)
    }

    @Test
    fun `an active shift is called out and left out of the totals`() {
        val notice = ActiveShiftNotice(
            WorksiteLabel.Registered("Office"),
            Instant.ofEpochMilli(millisAt("2026-09-10T08:15")),
        )
        val text = formatter.fullReport(
            report(clockIn("a", "2026-09-08T09:00"), clockOut("a", "2026-09-08T10:00")),
            notice,
        ).normalizeSpaces()

        assertTrue(
            text,
            "You're clocked in at Office since Thu 8:15 AM. That shift isn't included until you clock out." in text,
        )
        assertTrue(text, "Total worked: 1h 00m" in text)
    }

    @Test
    fun `an empty report says so instead of listing zeros`() {
        val text = formatter.fullReport(report(), activeShift = null)

        assertTrue(text, "No completed shifts in this period." in text)
        assertFalse(text, "By day" in text)
    }

    @Test
    fun `the change line reads up, down or the same`() {
        val base = report(clockIn("a", "2026-09-08T09:00"), clockOut("a", "2026-09-08T17:00"))

        assertEquals("Up 2h 00m from the two weeks before", formatter.change(BiweeklySummary(base, 6 * HOUR)))
        assertEquals("Down 1h 30m from the two weeks before", formatter.change(BiweeklySummary(base, 9 * HOUR + 30 * MINUTE)))
        assertEquals("Same as the two weeks before", formatter.change(BiweeklySummary(base, 8 * HOUR)))
    }

    @Test
    fun `the subject names the period type and dates`() {
        assertEquals("Attendance report: Week, Sep 6, 2026 – Sep 12, 2026", formatter.subject(week))
    }
}
