package com.jaustinjr.employeeattendance.reporting

import android.util.Log
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.settings.ReportSettings
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime

/** Schedules the recurring check that posts the biweekly report. Seam over WorkManager. */
interface ReportScheduler {
    fun setBiweeklyCheckScheduled(scheduled: Boolean)
}

/** Posts the biweekly summary notification. Seam over NotificationManager. */
interface ReportNotifications {
    fun notifyBiweekly(summary: BiweeklySummary)
}

/**
 * Owns the opt-in: keeps the persisted flag and the scheduled work in agreement.
 */
class BiweeklyReportController(
    private val settings: ReportSettings,
    private val scheduler: ReportScheduler,
    private val clock: Clock,
) {
    fun setEnabled(enabled: Boolean) {
        if (enabled && !settings.biweeklyNotificationEnabled.value) {
            // Opting in mid-period must not immediately post a summary of a fortnight that ended
            // long ago; the first notification is for the period now in progress.
            val today = ZonedDateTime.now(clock).toLocalDate()
            settings.lastNotifiedPeriodStart = ReportPeriod.lastCompletedBiweekly(today).start
        }
        settings.setBiweeklyNotificationEnabled(enabled)
        scheduler.setBiweeklyCheckScheduled(enabled)
    }

    /** Re-applies the persisted choice, e.g. after an app update cleared scheduled work. */
    fun reconcile() {
        scheduler.setBiweeklyCheckScheduled(settings.biweeklyNotificationEnabled.value)
    }
}

sealed interface BiweeklyDecision {
    data object Disabled : BiweeklyDecision
    data object AlreadyHandled : BiweeklyDecision
    data object TooEarly : BiweeklyDecision
    data class SkippedEmpty(val period: ReportPeriod) : BiweeklyDecision
    data class Notified(val summary: BiweeklySummary) : BiweeklyDecision
}

/**
 * One run of the daily background check.
 *
 * The check runs daily rather than every 14 days: a 14-day periodic job drifts later with every
 * deferred run, while a daily check always lands on the first run after the boundary. Each
 * period is handled exactly once, whether a notification was posted or skipped for having no
 * completed shifts.
 */
class BiweeklyReportRunner(
    private val settings: ReportSettings,
    private val attendanceRepository: AttendanceRepository,
    private val workLocationRepository: WorkLocationRepository,
    private val generator: ReportGenerator,
    private val notifier: ReportNotifications,
    private val clock: Clock,
) {
    fun run(): BiweeklyDecision {
        if (!settings.biweeklyNotificationEnabled.value) return BiweeklyDecision.Disabled

        val now = ZonedDateTime.now(clock)
        val period = ReportPeriod.lastCompletedBiweekly(now.toLocalDate())
        val handled = settings.lastNotifiedPeriodStart
        if (handled != null && handled >= period.start) return BiweeklyDecision.AlreadyHandled

        val deliverAfter = period.endExclusive.atTime(DELIVERY_TIME).atZone(now.zone)
        if (now.isBefore(deliverAfter)) return BiweeklyDecision.TooEarly

        val summary = generator.biweeklySummary(
            today = now.toLocalDate(),
            events = attendanceRepository.eventLog.value,
            worksites = workLocationRepository.workLocations.value,
            zone = now.zone,
        )
        settings.lastNotifiedPeriodStart = period.start
        if (summary.report.isEmpty) {
            Log.d(TAG, "no completed shifts for ${period.start}; not notifying")
            return BiweeklyDecision.SkippedEmpty(period)
        }
        notifier.notifyBiweekly(summary)
        return BiweeklyDecision.Notified(summary)
    }

    companion object {
        private const val TAG = "BiweeklyReport"

        /** Not before this time on the Sunday after a period closes. */
        val DELIVERY_TIME: LocalTime = LocalTime.of(9, 0)
    }
}
