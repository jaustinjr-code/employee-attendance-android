package com.jaustinjr.employeeattendance.reporting

import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The cached entry point for reports, shared by the Reports screen and the biweekly worker.
 *
 * Results are cached per (period, zone) and are valid only for the exact event-log and worksite
 * list instances they were computed from. Both repositories publish a fresh immutable list on every
 * change, so an identity check (`===`) is a complete and O(1) invalidation test: paging back to a
 * period already viewed, or returning to the tab, costs a map lookup instead of a recomputation.
 *
 * `@Synchronized` because the ViewModel computes on `Dispatchers.Default` while the worker runs on
 * WorkManager's executor.
 */
class ReportGenerator(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Key(val period: ReportPeriod, val zone: ZoneId)

    private class Entry(
        val events: List<AttendanceEvent>,
        val worksites: List<WorkLocation>,
        val report: AttendanceReport,
    )

    private val cache = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?) =
            size > maxEntries
    }

    private var sessionSource: List<AttendanceEvent>? = null
    private var sessionLog: SessionLog = SessionLog(emptyList(), emptyList())

    /** Number of reports actually computed rather than served from the cache. */
    var computeCount: Int = 0
        private set

    @Synchronized
    fun report(
        period: ReportPeriod,
        events: List<AttendanceEvent>,
        worksites: List<WorkLocation>,
        zone: ZoneId,
    ): AttendanceReport {
        val key = Key(period, zone)
        cache[key]?.let { entry ->
            if (entry.events === events && entry.worksites === worksites) return entry.report
        }
        computeCount++
        val report = ReportCalculator.calculate(period, sessions(events).completed, worksites, zone)
        cache[key] = Entry(events, worksites, report)
        return report
    }

    /**
     * The shift in progress that overlaps [period], if any. Not cached: it depends on [now], and is
     * a scan of the (already cached) open shifts, of which there is at most one per worksite.
     */
    @Synchronized
    fun activeShiftNotice(
        period: ReportPeriod,
        events: List<AttendanceEvent>,
        worksites: List<WorkLocation>,
        zone: ZoneId,
        now: Instant,
    ): ActiveShiftNotice? {
        val periodStart = period.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val periodEnd = period.endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val shift = sessions(events).active.lastOrNull {
            it.startMillis < periodEnd && now.toEpochMilli() > periodStart
        } ?: return null
        val names = worksites.associate { it.id to it.name }
        return ActiveShiftNotice(
            label = ReportCalculator.labelFor(shift.locationId, names),
            startedAt = Instant.ofEpochMilli(shift.startMillis),
        )
    }

    fun biweeklySummary(
        today: LocalDate,
        events: List<AttendanceEvent>,
        worksites: List<WorkLocation>,
        zone: ZoneId,
    ): BiweeklySummary {
        val period = ReportPeriod.lastCompletedBiweekly(today)
        return BiweeklySummary(
            report = report(period, events, worksites, zone),
            previousTotalMillis = report(period.previous(), events, worksites, zone).totalMillis,
        )
    }

    private fun sessions(events: List<AttendanceEvent>): SessionLog {
        if (sessionSource !== events) {
            sessionLog = SessionLog.from(events)
            sessionSource = events
        }
        return sessionLog
    }

    private companion object {
        /** A month, a week and a biweekly view each paged a few periods back. */
        const val DEFAULT_MAX_ENTRIES = 16
    }
}
