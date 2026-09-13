package com.jaustinjr.employeeattendance.reporting

import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCalculatorTest {

    private val week = ReportPeriod.containing(ReportPeriodType.WEEK, LocalDate.of(2026, 9, 10))

    private fun report(
        vararg sessions: Pair<String, String>,
        period: ReportPeriod = week,
        locationId: String = "a",
        worksites: List<com.jaustinjr.employeeattendance.location.registration.WorkLocation> =
            listOf(worksite("a", "Office")),
    ): AttendanceReport = ReportCalculator.calculate(
        period = period,
        sessions = sessions.map { (start, end) -> WorkSession(locationId, millisAt(start), millisAt(end)) },
        worksites = worksites,
        zone = NEW_YORK,
    )

    private fun AttendanceReport.hoursOn(date: String): Double =
        days.single { it.date == LocalDate.parse(date) }.workedMillis / HOUR.toDouble()

    @Test
    fun `an empty period has a zero entry for every day`() {
        val report = report()

        assertTrue(report.isEmpty)
        assertEquals(7, report.days.size)
        assertTrue(report.days.all { it.workedMillis == 0L })
        assertEquals(0, report.daysWorked)
        assertNull(report.averageClockIn)
    }

    @Test
    fun `totals, days worked and averages`() {
        val report = report(
            "2026-09-07T09:00" to "2026-09-07T17:00",
            "2026-09-08T08:00" to "2026-09-08T18:00",
            "2026-09-08T19:00" to "2026-09-08T20:00",
        )

        assertEquals(19 * HOUR, report.totalMillis)
        assertEquals(3, report.shiftCount)
        assertEquals(2, report.daysWorked)
        assertEquals(19 * HOUR / 2, report.averageMillisPerWorkedDay)
        assertEquals(10 * HOUR, report.longestShiftMillis)
        assertEquals(8.0, report.hoursOn("2026-09-07"), 0.0)
        assertEquals(11.0, report.hoursOn("2026-09-08"), 0.0)
        // (09:00 + 08:00 + 19:00) / 3 = 12:00
        assertEquals(LocalTime.of(12, 0), report.averageClockIn)
    }

    @Test
    fun `an overnight shift is split at midnight`() {
        val report = report("2026-09-07T22:00" to "2026-09-08T06:00")

        assertEquals(2.0, report.hoursOn("2026-09-07"), 0.0)
        assertEquals(6.0, report.hoursOn("2026-09-08"), 0.0)
        assertEquals(2, report.daysWorked)
        assertEquals(1, report.shiftCount)
    }

    @Test
    fun `a shift crossing the period boundary counts only the part inside it`() {
        // The week starts Sunday 2026-09-06 00:00.
        val report = report("2026-09-05T20:00" to "2026-09-06T04:00")

        assertEquals(4 * HOUR, report.totalMillis)
        assertEquals(4.0, report.hoursOn("2026-09-06"), 0.0)
        // It started before the period, so it does not move the average clock-in.
        assertNull(report.averageClockIn)
        // Longest shift is the whole shift, as the user experienced it.
        assertEquals(8 * HOUR, report.longestShiftMillis)
    }

    @Test
    fun `shifts entirely outside the period are ignored`() {
        val report = report(
            "2026-09-01T09:00" to "2026-09-01T17:00",
            "2026-09-13T09:00" to "2026-09-13T17:00",
        )

        assertTrue(report.isEmpty)
        assertEquals(0L, report.totalMillis)
    }

    @Test
    fun `an overnight shift into the spring-forward day credits real elapsed time`() {
        // 2026-03-08 02:00 does not exist in New York; 22:00 to 06:00 is seven real hours.
        val period = ReportPeriod.containing(ReportPeriodType.MONTH, LocalDate.of(2026, 3, 8))
        val report = report("2026-03-07T22:00" to "2026-03-08T06:00", period = period)

        assertEquals(2.0, report.hoursOn("2026-03-07"), 0.0)
        assertEquals(5.0, report.hoursOn("2026-03-08"), 0.0)
        assertEquals(7 * HOUR, report.totalMillis)
    }

    @Test
    fun `a month on the fall-back day has a 25-hour day`() {
        val period = ReportPeriod.containing(ReportPeriodType.MONTH, LocalDate.of(2026, 11, 1))
        val report = report("2026-11-01T00:00" to "2026-11-02T00:00", period = period)

        assertEquals(25.0, report.hoursOn("2026-11-01"), 0.0)
        assertEquals(30, report.days.size)
    }

    @Test
    fun `worksites are ordered by time and labeled`() {
        val report = ReportCalculator.calculate(
            period = week,
            sessions = listOf(
                WorkSession("a", millisAt("2026-09-07T09:00"), millisAt("2026-09-07T10:00")),
                WorkSession(
                    AttendanceRepository.GENERAL_TIMECLOCK_ID,
                    millisAt("2026-09-08T09:00"),
                    millisAt("2026-09-08T12:00"),
                ),
                WorkSession("gone", millisAt("2026-09-09T09:00"), millisAt("2026-09-09T11:00")),
            ),
            worksites = listOf(worksite("a", "Office")),
            zone = NEW_YORK,
        )

        assertEquals(
            listOf(
                WorksiteTotal(AttendanceRepository.GENERAL_TIMECLOCK_ID, WorksiteLabel.GeneralTimeclock, 3 * HOUR),
                WorksiteTotal("gone", WorksiteLabel.Removed, 2 * HOUR),
                WorksiteTotal("a", WorksiteLabel.Registered("Office"), 1 * HOUR),
            ),
            report.worksites,
        )
    }
}
