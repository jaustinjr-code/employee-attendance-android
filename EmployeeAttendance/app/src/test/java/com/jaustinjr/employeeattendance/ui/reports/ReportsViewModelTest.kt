package com.jaustinjr.employeeattendance.ui.reports

import com.jaustinjr.employeeattendance.attendance.RecordingAttendanceRepository
import com.jaustinjr.employeeattendance.devtools.FakeWorkLocationRepository
import com.jaustinjr.employeeattendance.reporting.ActiveShiftNotice
import com.jaustinjr.employeeattendance.reporting.AttendanceReport
import com.jaustinjr.employeeattendance.reporting.HOUR
import com.jaustinjr.employeeattendance.reporting.ReportGenerator
import com.jaustinjr.employeeattendance.reporting.ReportPeriodType
import com.jaustinjr.employeeattendance.reporting.ReportShareResult
import com.jaustinjr.employeeattendance.reporting.ReportSharer
import com.jaustinjr.employeeattendance.reporting.ShareTarget
import com.jaustinjr.employeeattendance.reporting.WorksiteLabel
import com.jaustinjr.employeeattendance.reporting.clockAt
import com.jaustinjr.employeeattendance.reporting.millisAt
import com.jaustinjr.employeeattendance.reporting.worksite
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class RecordingSharer(var result: ReportShareResult = ReportShareResult.Launched) : ReportSharer {
        val shared = mutableListOf<Triple<AttendanceReport, ActiveShiftNotice?, ShareTarget>>()
        override suspend fun share(
            report: AttendanceReport,
            activeShift: ActiveShiftNotice?,
            target: ShareTarget,
        ): ReportShareResult {
            shared += Triple(report, activeShift, target)
            return result
        }
    }

    private val attendance = RecordingAttendanceRepository()
    private val worksites = FakeWorkLocationRepository().apply { registerWorkLocation(worksite("a", "Office")) }
    private val generator = ReportGenerator()
    private val sharer = RecordingSharer()

    // Thursday; the current week is 2026-09-06..09-12.
    private fun viewModel(compute: CoroutineDispatcher = mainRule.dispatcher) = ReportsViewModel(
        attendanceRepository = attendance,
        workLocationRepository = worksites,
        generator = generator,
        sharer = sharer,
        clock = clockAt("2026-09-10T12:00"),
        computeDispatcher = compute,
    )

    private fun TestScope.subscribe(vm: ReportsViewModel) = launch {
        launch { vm.selection.collect {} }
        launch { vm.report.collect {} }
        launch { vm.biweekly.collect {} }
    }

    private val ReportsViewModel.ready: ReportSection.Ready
        get() = report.value as ReportSection.Ready

    @Test
    fun `opens on the current week with no way forward`() = runTest {
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()

        val selection = vm.selection.value
        assertEquals(ReportPeriodType.WEEK, selection.period.type)
        assertEquals(LocalDate.of(2026, 9, 6), selection.period.start)
        assertFalse(selection.canGoNext)
        job.cancel()
    }

    @Test
    fun `nothing is computed until the screen collects`() = runTest {
        val vm = viewModel()
        runCurrent()

        assertEquals(ReportSection.Loading, vm.report.value)
        assertEquals(0, generator.computeCount)
    }

    @Test
    fun `the report excludes the active shift and flags it`() = runTest {
        attendance.recordClockIn("a", millisAt("2026-09-08T09:00"))
        attendance.recordClockOut("a", millisAt("2026-09-08T17:00"))
        attendance.recordClockIn("a", millisAt("2026-09-10T08:00"))
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()

        assertEquals(8 * HOUR, vm.ready.report.totalMillis)
        assertEquals(WorksiteLabel.Registered("Office"), vm.ready.activeShift?.label)
        job.cancel()
    }

    @Test
    fun `a new clock-out updates the open report`() = runTest {
        attendance.recordClockIn("a", millisAt("2026-09-10T08:00"))
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()
        assertTrue(vm.ready.report.isEmpty)

        attendance.recordClockOut("a", millisAt("2026-09-10T11:00"))
        runCurrent()

        assertEquals(3 * HOUR, vm.ready.report.totalMillis)
        assertNull(vm.ready.activeShift)
        job.cancel()
    }

    @Test
    fun `switching to month keeps today in view`() = runTest {
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()

        vm.onPeriodTypeSelected(ReportPeriodType.MONTH)
        runCurrent()

        assertEquals(LocalDate.of(2026, 9, 1), vm.selection.value.period.start)
        assertEquals(ReportPeriodType.MONTH, vm.ready.report.period.type)
        job.cancel()
    }

    @Test
    fun `switching type from a past period anchors on its start`() = runTest {
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()

        vm.onPreviousPeriod() // 2026-08-30..09-05
        vm.onPeriodTypeSelected(ReportPeriodType.MONTH)
        runCurrent()

        assertEquals(LocalDate.of(2026, 8, 1), vm.selection.value.period.start)
        job.cancel()
    }

    @Test
    fun `paging forward stops at the current period`() = runTest {
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()

        vm.onPreviousPeriod()
        runCurrent()
        assertTrue(vm.selection.value.canGoNext)

        vm.onNextPeriod()
        vm.onNextPeriod()
        runCurrent()
        assertEquals(LocalDate.of(2026, 9, 6), vm.selection.value.period.start)
        job.cancel()
    }

    @Test
    fun `returning to a period already viewed is not recomputed`() = runTest {
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()
        val afterFirstLoad = generator.computeCount

        vm.onPreviousPeriod()
        runCurrent()
        vm.onNextPeriod()
        runCurrent()

        // One new computation for the previous week; the current week came from the cache.
        assertEquals(afterFirstLoad + 1, generator.computeCount)
        job.cancel()
    }

    @Test
    fun `rapid paging computes only the period the user lands on`() = runTest {
        val compute = StandardTestDispatcher(testScheduler)
        val vm = viewModel(compute)
        val job = subscribe(vm)
        runCurrent()
        val afterFirstLoad = generator.computeCount

        vm.onPreviousPeriod()
        vm.onPreviousPeriod()
        vm.onPreviousPeriod()
        runCurrent()

        assertEquals(LocalDate.of(2026, 8, 16), vm.ready.report.period.start)
        assertEquals(afterFirstLoad + 1, generator.computeCount)
        job.cancel()
    }

    @Test
    fun `share hands over the displayed report and its notice`() = runTest {
        attendance.recordClockIn("a", millisAt("2026-09-10T08:00"))
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()

        vm.onShare(ShareTarget.EMAIL)
        runCurrent()

        val (report, notice, target) = sharer.shared.single()
        assertEquals(vm.ready.report, report)
        assertEquals(vm.ready.activeShift, notice)
        assertEquals(ShareTarget.EMAIL, target)
        assertNull(vm.shareFailure.value)
        job.cancel()
    }

    @Test
    fun `a failed share is surfaced once`() = runTest {
        sharer.result = ReportShareResult.NoApp
        val vm = viewModel()
        val job = subscribe(vm)
        runCurrent()

        vm.onShare(ShareTarget.ANY_APP)
        runCurrent()
        assertEquals(ReportShareResult.NoApp, vm.shareFailure.value)

        vm.onShareFailureShown()
        assertNull(vm.shareFailure.value)
        job.cancel()
    }

    @Test
    fun `share before the report loads does nothing`() = runTest {
        val vm = viewModel()

        vm.onShare(ShareTarget.ANY_APP)
        runCurrent()

        assertTrue(sharer.shared.isEmpty())
    }
}
