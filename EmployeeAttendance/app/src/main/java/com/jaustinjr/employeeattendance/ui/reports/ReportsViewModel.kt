package com.jaustinjr.employeeattendance.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.reporting.ActiveShiftNotice
import com.jaustinjr.employeeattendance.reporting.AttendanceReport
import com.jaustinjr.employeeattendance.reporting.BiweeklySummary
import com.jaustinjr.employeeattendance.reporting.ReportGenerator
import com.jaustinjr.employeeattendance.reporting.ReportPeriod
import com.jaustinjr.employeeattendance.reporting.ReportPeriodType
import com.jaustinjr.employeeattendance.reporting.ReportShareResult
import com.jaustinjr.employeeattendance.reporting.ReportSharer
import com.jaustinjr.employeeattendance.reporting.ShareTarget
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The period being viewed, and whether the user can page forward from it. */
data class PeriodSelection(val period: ReportPeriod, val canGoNext: Boolean)

sealed interface ReportSection {
    data object Loading : ReportSection
    data class Ready(val report: AttendanceReport, val activeShift: ActiveShiftNotice?) : ReportSection
}

sealed interface BiweeklySection {
    data object Loading : BiweeklySection
    data class Ready(val summary: BiweeklySummary) : BiweeklySection
}

/**
 * Backs the Reports tab.
 *
 * ### Load cost
 * - Nothing is computed until the tab is first opened: this ViewModel is scoped to the Reports
 *   back-stack entry, and the bottom bar saves and restores that entry, so switching tabs keeps it.
 * - Each section is its own [StateFlow], so a new report recomposes the report sections but leaves
 *   the biweekly card skipped, and vice versa.
 * - [ReportGenerator] runs on [computeDispatcher]; `mapLatest` cancels a computation the user has
 *   already paged past. The generator's cache makes revisiting a period free.
 * - `WhileSubscribed(5_000)`: collection stops five seconds after the screen stops (the UI collects
 *   with `collectAsStateWithLifecycle`, i.e. until `onStop`, not `onPause`, so a report that is still
 *   visible behind a dialog or in multi-window keeps updating). A rotation re-subscribes within the
 *   window and recomputes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(
    attendanceRepository: AttendanceRepository,
    workLocationRepository: WorkLocationRepository,
    private val generator: ReportGenerator,
    private val sharer: ReportSharer,
    private val clock: Clock,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val period = MutableStateFlow(ReportPeriod.containing(ReportPeriodType.WEEK, today()))

    val selection: StateFlow<PeriodSelection> = period
        .map(::selectionFor)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), selectionFor(period.value))

    val report: StateFlow<ReportSection> = combine(
        period,
        attendanceRepository.eventLog,
        workLocationRepository.workLocations,
    ) { period, events, worksites -> Triple(period, events, worksites) }
        .mapLatest { (period, events, worksites) ->
            withContext(computeDispatcher) {
                ReportSection.Ready(
                    report = generator.report(period, events, worksites, clock.zone),
                    activeShift = generator.activeShiftNotice(
                        period, events, worksites, clock.zone, clock.instant(),
                    ),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReportSection.Loading)

    val biweekly: StateFlow<BiweeklySection> = combine(
        attendanceRepository.eventLog,
        workLocationRepository.workLocations,
    ) { events, worksites -> events to worksites }
        .mapLatest { (events, worksites) ->
            withContext(computeDispatcher) {
                BiweeklySection.Ready(generator.biweeklySummary(today(), events, worksites, clock.zone))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BiweeklySection.Loading)

    private val _shareFailure = MutableStateFlow<ReportShareResult?>(null)

    /** A failed share to surface once, then clear with [onShareFailureShown]. */
    val shareFailure: StateFlow<ReportShareResult?> = _shareFailure.asStateFlow()

    fun onPeriodTypeSelected(type: ReportPeriodType) {
        val current = period.value
        if (current.type == type) return
        val today = today()
        // Stay on the present when viewing it; otherwise keep the start of what was being viewed.
        val anchor = if (today in current) today else current.start
        period.value = ReportPeriod.containing(type, anchor)
    }

    fun onPreviousPeriod() {
        period.value = period.value.previous()
    }

    fun onNextPeriod() {
        val next = period.value.next()
        if (next.start <= today()) period.value = next
    }

    fun onShare(target: ShareTarget) {
        val ready = report.value as? ReportSection.Ready ?: return
        viewModelScope.launch {
            val result = sharer.share(ready.report, ready.activeShift, target)
            if (result != ReportShareResult.Launched) _shareFailure.value = result
        }
    }

    fun onShareFailureShown() {
        _shareFailure.value = null
    }

    private fun selectionFor(period: ReportPeriod) =
        PeriodSelection(period, canGoNext = period.endExclusive <= today())

    private fun today(): LocalDate = LocalDate.now(clock)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                ReportsViewModel(
                    attendanceRepository = container.attendanceRepository,
                    workLocationRepository = container.workLocationRepository,
                    generator = container.reportGenerator,
                    sharer = container.reportSharer,
                    clock = container.clock,
                )
            }
        }
    }
}
