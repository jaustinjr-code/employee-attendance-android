package com.jaustinjr.employeeattendance.reporting

import com.jaustinjr.employeeattendance.attendance.RecordingAttendanceRepository
import com.jaustinjr.employeeattendance.devtools.FakeWorkLocationRepository
import com.jaustinjr.employeeattendance.settings.ReportSettings
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeReportSettings(enabled: Boolean = false) : ReportSettings {
    private val _enabled = MutableStateFlow(enabled)
    override val biweeklyNotificationEnabled: StateFlow<Boolean> = _enabled
    override fun setBiweeklyNotificationEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }
    override var lastNotifiedPeriodStart: LocalDate? = null
}

class RecordingReportScheduler : ReportScheduler {
    val calls = mutableListOf<Boolean>()
    override fun setBiweeklyCheckScheduled(scheduled: Boolean) {
        calls += scheduled
    }
}

class RecordingReportNotifier : ReportNotifications {
    val posted = mutableListOf<BiweeklySummary>()
    override fun notifyBiweekly(summary: BiweeklySummary) {
        posted += summary
    }
}

fun clockAt(text: String): Clock =
    Clock.fixed(ZonedDateTime.of(java.time.LocalDateTime.parse(text), NEW_YORK).toInstant(), NEW_YORK)

class BiweeklyReportControllerTest {

    private val settings = FakeReportSettings()
    private val scheduler = RecordingReportScheduler()

    @Test
    fun `opting in schedules the check and skips periods that already ended`() {
        // 2026-09-10: the last completed biweekly period started 2026-08-23.
        BiweeklyReportController(settings, scheduler, clockAt("2026-09-10T12:00")).setEnabled(true)

        assertTrue(settings.biweeklyNotificationEnabled.value)
        assertEquals(listOf(true), scheduler.calls)
        assertEquals(LocalDate.of(2026, 8, 23), settings.lastNotifiedPeriodStart)
    }

    @Test
    fun `opting out cancels the check`() {
        settings.setBiweeklyNotificationEnabled(true)
        BiweeklyReportController(settings, scheduler, clockAt("2026-09-10T12:00")).setEnabled(false)

        assertEquals(false, settings.biweeklyNotificationEnabled.value)
        assertEquals(listOf(false), scheduler.calls)
    }

    @Test
    fun `re-enabling while already enabled keeps the bookkeeping`() {
        settings.setBiweeklyNotificationEnabled(true)
        settings.lastNotifiedPeriodStart = LocalDate.of(2026, 8, 9)

        BiweeklyReportController(settings, scheduler, clockAt("2026-09-10T12:00")).setEnabled(true)

        assertEquals(LocalDate.of(2026, 8, 9), settings.lastNotifiedPeriodStart)
    }

    @Test
    fun `reconcile applies the persisted choice`() {
        settings.setBiweeklyNotificationEnabled(true)
        BiweeklyReportController(settings, scheduler, clockAt("2026-09-10T12:00")).reconcile()

        assertEquals(listOf(true), scheduler.calls)
    }
}

class BiweeklyReportRunnerTest {

    private val settings = FakeReportSettings(enabled = true)
    private val attendance = RecordingAttendanceRepository()
    private val worksites = FakeWorkLocationRepository().apply {
        registerWorkLocation(worksite("a", "Office"))
    }
    private val notifier = RecordingReportNotifier()

    // Biweekly periods: 2026-08-23..09-05, 2026-09-06..09-19, 2026-09-20..10-03.
    private fun runner(now: String) = BiweeklyReportRunner(
        settings = settings,
        attendanceRepository = attendance,
        workLocationRepository = worksites,
        generator = ReportGenerator(),
        notifier = notifier,
        clock = clockAt(now),
    )

    private fun work(start: String, end: String) {
        attendance.recordClockIn("a", millisAt(start))
        attendance.recordClockOut("a", millisAt(end))
    }

    @Test
    fun `posts once for a period that closed, after the delivery time`() {
        settings.lastNotifiedPeriodStart = LocalDate.of(2026, 8, 23)
        work("2026-09-08T09:00", "2026-09-08T17:00")

        val decision = runner("2026-09-20T09:30").run()

        assertTrue(decision is BiweeklyDecision.Notified)
        assertEquals(8 * HOUR, notifier.posted.single().report.totalMillis)
        assertEquals(LocalDate.of(2026, 9, 6), settings.lastNotifiedPeriodStart)
        assertEquals(BiweeklyDecision.AlreadyHandled, runner("2026-09-21T09:30").run())
        assertEquals(1, notifier.posted.size)
    }

    @Test
    fun `waits until the delivery time on the Sunday the period closes`() {
        settings.lastNotifiedPeriodStart = LocalDate.of(2026, 8, 23)
        work("2026-09-08T09:00", "2026-09-08T17:00")

        assertEquals(BiweeklyDecision.TooEarly, runner("2026-09-20T08:59").run())
        assertEquals(emptyList<BiweeklySummary>(), notifier.posted)
    }

    @Test
    fun `a period with no completed shifts is marked handled without a notification`() {
        settings.lastNotifiedPeriodStart = LocalDate.of(2026, 8, 23)
        // Still clocked in: the open shift does not count.
        attendance.recordClockIn("a", millisAt("2026-09-19T09:00"))

        val decision = runner("2026-09-20T10:00").run()

        assertEquals(BiweeklyDecision.SkippedEmpty(ReportPeriod.lastCompletedBiweekly(LocalDate.of(2026, 9, 20))), decision)
        assertEquals(emptyList<BiweeklySummary>(), notifier.posted)
        assertEquals(LocalDate.of(2026, 9, 6), settings.lastNotifiedPeriodStart)
    }

    @Test
    fun `does nothing when the user has not opted in`() {
        settings.setBiweeklyNotificationEnabled(false)
        work("2026-09-08T09:00", "2026-09-08T17:00")

        assertEquals(BiweeklyDecision.Disabled, runner("2026-09-20T10:00").run())
        assertEquals(emptyList<BiweeklySummary>(), notifier.posted)
    }

    @Test
    fun `a run days late still reports the most recent closed period only`() {
        settings.lastNotifiedPeriodStart = LocalDate.of(2026, 8, 23)
        work("2026-09-22T09:00", "2026-09-22T12:00")

        val decision = runner("2026-10-05T10:00").run() as BiweeklyDecision.Notified

        assertEquals(LocalDate.of(2026, 9, 20), decision.summary.report.period.start)
        assertEquals(1, notifier.posted.size)
    }
}
