package com.jaustinjr.employeeattendance.reporting

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReportGeneratorTest {

    private val generator = ReportGenerator(maxEntries = 3)
    private val week = ReportPeriod.containing(ReportPeriodType.WEEK, LocalDate.of(2026, 9, 10))
    private val worksites = listOf(worksite("a", "Office"))
    private val events = listOf(clockIn("a", "2026-09-08T09:00"), clockOut("a", "2026-09-08T17:00"))

    @Test
    fun `the same period over the same lists is served from the cache`() {
        val first = generator.report(week, events, worksites, NEW_YORK)
        val second = generator.report(week, events, worksites, NEW_YORK)

        assertSame(first, second)
        assertEquals(1, generator.computeCount)
    }

    @Test
    fun `a new event log instance invalidates, even with equal contents`() {
        generator.report(week, events, worksites, NEW_YORK)
        generator.report(week, events.toList(), worksites, NEW_YORK)

        assertEquals(2, generator.computeCount)
    }

    @Test
    fun `a new worksite list invalidates so renamed worksites show their new name`() {
        generator.report(week, events, worksites, NEW_YORK)
        val renamed = generator.report(week, events, listOf(worksite("a", "HQ")), NEW_YORK)

        assertEquals(WorksiteLabel.Registered("HQ"), renamed.worksites.single().label)
        assertEquals(2, generator.computeCount)
    }

    @Test
    fun `paging back to an earlier period is a cache hit`() {
        generator.report(week, events, worksites, NEW_YORK)
        generator.report(week.previous(), events, worksites, NEW_YORK)
        generator.report(week, events, worksites, NEW_YORK)

        assertEquals(2, generator.computeCount)
    }

    @Test
    fun `the zone is part of the key`() {
        generator.report(week, events, worksites, NEW_YORK)
        generator.report(week, events, worksites, ZoneOffset.UTC)

        assertEquals(2, generator.computeCount)
    }

    @Test
    fun `the least recently used entry is evicted`() {
        generator.report(week, events, worksites, NEW_YORK)
        generator.report(week.previous(), events, worksites, NEW_YORK)
        generator.report(week.previous().previous(), events, worksites, NEW_YORK)
        generator.report(week.next(), events, worksites, NEW_YORK)
        generator.report(week, events, worksites, NEW_YORK)

        assertEquals(5, generator.computeCount)
    }

    @Test
    fun `an active shift overlapping the period produces a notice`() {
        val log = events + clockIn("a", "2026-09-10T08:00")
        val now = Instant.ofEpochMilli(millisAt("2026-09-10T12:00"))

        val notice = generator.activeShiftNotice(week, log, worksites, NEW_YORK, now)

        assertEquals(
            ActiveShiftNotice(WorksiteLabel.Registered("Office"), Instant.ofEpochMilli(millisAt("2026-09-10T08:00"))),
            notice,
        )
        // And it is not counted.
        assertEquals(8 * HOUR, generator.report(week, log, worksites, NEW_YORK).totalMillis)
    }

    @Test
    fun `an active shift that started after the period shows no notice for it`() {
        val log = events + clockIn("a", "2026-09-14T08:00")
        val now = Instant.ofEpochMilli(millisAt("2026-09-14T12:00"))

        assertNull(generator.activeShiftNotice(week, log, worksites, NEW_YORK, now))
        assertNotNull(generator.activeShiftNotice(week.next(), log, worksites, NEW_YORK, now))
    }

    @Test
    fun `the biweekly summary compares with the period before`() {
        // Last completed biweekly period before 2026-09-10 runs 2026-08-23 to 2026-09-05.
        val today = LocalDate.of(2026, 9, 10)
        val period = ReportPeriod.lastCompletedBiweekly(today)
        assertEquals(LocalDate.of(2026, 8, 23), period.start)
        val log = listOf(
            clockIn("a", "2026-08-12T09:00"), clockOut("a", "2026-08-12T13:00"),
            clockIn("a", "2026-08-24T09:00"), clockOut("a", "2026-08-24T15:00"),
        )

        val summary = generator.biweeklySummary(today, log, worksites, NEW_YORK)

        assertEquals(period, summary.report.period)
        assertEquals(6 * HOUR, summary.report.totalMillis)
        assertEquals(4 * HOUR, summary.previousTotalMillis)
        assertEquals(2 * HOUR, summary.changeMillis)
    }
}
