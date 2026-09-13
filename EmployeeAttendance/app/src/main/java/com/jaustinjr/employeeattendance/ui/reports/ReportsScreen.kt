package com.jaustinjr.employeeattendance.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingFlat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.reporting.ActiveShiftNotice
import com.jaustinjr.employeeattendance.reporting.AttendanceReport
import com.jaustinjr.employeeattendance.reporting.BiweeklySummary
import com.jaustinjr.employeeattendance.reporting.DayTotal
import com.jaustinjr.employeeattendance.reporting.ReportPeriod
import com.jaustinjr.employeeattendance.reporting.ReportPeriodType
import com.jaustinjr.employeeattendance.reporting.ReportShareResult
import com.jaustinjr.employeeattendance.reporting.ReportStrings
import com.jaustinjr.employeeattendance.reporting.ReportTextFormatter
import com.jaustinjr.employeeattendance.reporting.ShareTarget
import com.jaustinjr.employeeattendance.reporting.WorksiteLabel
import com.jaustinjr.employeeattendance.reporting.WorksiteTotal
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Callbacks for [ReportsContent], grouped so the whole set is one stable parameter. */
@Immutable
data class ReportsActions(
    val onPeriodTypeSelected: (ReportPeriodType) -> Unit = {},
    val onPreviousPeriod: () -> Unit = {},
    val onNextPeriod: () -> Unit = {},
    val onShare: (ShareTarget) -> Unit = {},
)

@Composable
fun ReportsScreen(
    modifier: Modifier = Modifier,
    viewModel: ReportsViewModel = viewModel(factory = ReportsViewModel.Factory),
) {
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val biweekly by viewModel.biweekly.collectAsStateWithLifecycle()
    val shareFailure by viewModel.shareFailure.collectAsStateWithLifecycle()

    // Built once per ViewModel: method references to a stable receiver, so recomposition never
    // hands the children new lambdas.
    val actions = remember(viewModel) {
        ReportsActions(
            onPeriodTypeSelected = viewModel::onPeriodTypeSelected,
            onPreviousPeriod = viewModel::onPreviousPeriod,
            onNextPeriod = viewModel::onNextPeriod,
            onShare = viewModel::onShare,
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val writeFailedMessage = stringResource(R.string.reports_share_failed)
    val noAppMessage = stringResource(R.string.reports_share_no_app)
    LaunchedEffect(shareFailure) {
        val failure = shareFailure ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            if (failure == ReportShareResult.NoApp) noAppMessage else writeFailedMessage,
        )
        viewModel.onShareFailureShown()
    }

    Box(modifier.fillMaxSize()) {
        ReportsContent(
            selection = selection,
            report = report,
            biweekly = biweekly,
            actions = actions,
        )
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

/** Formats values in the current locale; remembered per configuration, not per recomposition. */
@Composable
private fun rememberReportFormatter(): ReportTextFormatter {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) {
        ReportTextFormatter(
            ReportStrings.fromResources(resources),
            ConfigurationCompat.getLocales(configuration)[0] ?: java.util.Locale.getDefault(),
            ZoneId.systemDefault(),
        )
    }
}

/**
 * The Reports tab. Stateless: every section takes only its own slice of state, so with strong
 * skipping an unchanged section is skipped when another one updates.
 */
@Composable
fun ReportsContent(
    selection: PeriodSelection,
    report: ReportSection,
    biweekly: BiweeklySection,
    actions: ReportsActions,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberReportFormatter()
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(REPORTS_LIST_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "biweekly", contentType = "biweekly") {
            BiweeklySummaryCard(biweekly, formatter)
        }
        item(key = "selector", contentType = "selector") {
            PeriodSelector(selection, formatter, actions)
        }
        when (report) {
            ReportSection.Loading -> item(key = "loading", contentType = "skeleton") {
                ReportSkeleton()
            }
            is ReportSection.Ready -> {
                report.activeShift?.let { notice ->
                    item(key = "active", contentType = "active") { ActiveShiftCard(notice, formatter) }
                }
                if (report.report.isEmpty) {
                    item(key = "empty", contentType = "empty") { EmptyReport() }
                } else {
                    item(key = "stats", contentType = "stats") { StatGrid(report.report, formatter) }
                    item(key = "daily", contentType = "chart") { DailyChartCard(report.report, formatter) }
                    item(key = "worksites", contentType = "chart") {
                        WorksiteChartCard(report.report, formatter)
                    }
                }
                item(key = "share", contentType = "share") { ShareRow(actions) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun BiweeklySummaryCard(section: BiweeklySection, formatter: ReportTextFormatter) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().testTag(BIWEEKLY_CARD_TAG),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.reports_biweekly_title))
            when (section) {
                BiweeklySection.Loading -> SkeletonBlock(height = 72.dp)
                is BiweeklySection.Ready -> BiweeklySummaryBody(section.summary, formatter)
            }
        }
    }
}

@Composable
private fun BiweeklySummaryBody(summary: BiweeklySummary, formatter: ReportTextFormatter) {
    Text(
        text = formatter.dateRange(summary.report.period),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = formatter.duration(summary.report.totalMillis),
        style = MaterialTheme.typography.displaySmall,
    )
    Text(
        text = formatter.shiftCount(summary.report.shiftCount),
        style = MaterialTheme.typography.bodyLarge,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = when {
            summary.changeMillis >= 60_000 -> Icons.AutoMirrored.Outlined.TrendingUp
            summary.changeMillis <= -60_000 -> Icons.AutoMirrored.Outlined.TrendingDown
            else -> Icons.AutoMirrored.Outlined.TrendingFlat
        }
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(formatter.change(summary), style = MaterialTheme.typography.bodyMedium)
    }
    summary.topWorksite?.let {
        Text(
            text = stringResource(R.string.reports_biweekly_top_worksite, formatter.worksite(it.label)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(
    selection: PeriodSelection,
    formatter: ReportTextFormatter,
    actions: ReportsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf(ReportPeriodType.WEEK, ReportPeriodType.MONTH)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = selection.period.type == type,
                    onClick = { actions.onPeriodTypeSelected(type) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(formatter.periodLabel(type))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onPreviousPeriod) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.reports_previous_period),
                )
            }
            Text(
                text = formatter.dateRange(selection.period),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = actions.onNextPeriod, enabled = selection.canGoNext) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.reports_next_period),
                )
            }
        }
    }
}

@Composable
private fun ActiveShiftCard(notice: ActiveShiftNotice, formatter: ReportTextFormatter) {
    // A neutral container with an accent icon: dynamic tertiaryContainer can stay bright in dark
    // themes, which made an informational note louder than the report it annotates.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth().testTag(ACTIVE_SHIFT_TAG),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Text(formatter.activeShiftNote(notice), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyReport() {
    Text(
        text = stringResource(R.string.reports_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun StatGrid(report: AttendanceReport, formatter: ReportTextFormatter) {
    val none = stringResource(R.string.reports_stat_none)
    val timeFormat = remember(formatter) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val stats = listOf(
        stringResource(R.string.reports_stat_total) to formatter.duration(report.totalMillis),
        stringResource(R.string.reports_stat_shifts) to report.shiftCount.toString(),
        stringResource(R.string.reports_stat_days_worked) to report.daysWorked.toString(),
        stringResource(R.string.reports_stat_average_per_day) to
            formatter.duration(report.averageMillisPerWorkedDay),
        stringResource(R.string.reports_stat_longest_shift) to formatter.duration(report.longestShiftMillis),
        stringResource(R.string.reports_stat_average_clock_in) to
            (report.averageClockIn?.let(timeFormat::format) ?: none),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    StatTile(label, value, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun DailyChartCard(report: AttendanceReport, formatter: ReportTextFormatter) {
    val configuration = LocalConfiguration.current
    val locale = ConfigurationCompat.getLocales(configuration)[0] ?: java.util.Locale.getDefault()
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d", locale) }
    val spoken = remember(report.days, formatter) {
        report.days.joinToString("; ") { "${dayFormat.format(it.date)}: ${formatter.duration(it.workedMillis)}" }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.reports_chart_daily_title))
            DailyHoursChart(
                days = report.days,
                periodType = report.period.type,
                locale = locale,
                description = stringResource(R.string.reports_chart_daily_description, spoken),
            )
        }
    }
}

@Composable
private fun WorksiteChartCard(report: AttendanceReport, formatter: ReportTextFormatter) {
    val resources = LocalResources.current
    val spoken = remember(report.worksites, formatter, resources) {
        report.worksites.joinToString("; ") {
            val percent = if (report.totalMillis == 0L) 0 else (it.workedMillis * 100 / report.totalMillis).toInt()
            resources.getString(
                R.string.reports_worksite_share,
                formatter.worksite(it.label),
                formatter.duration(it.workedMillis),
                percent,
            )
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.reports_chart_worksite_title))
            WorksiteShareChart(
                worksites = report.worksites,
                totalMillis = report.totalMillis,
                worksiteName = { formatter.worksite(it.label) },
                duration = formatter::duration,
                description = stringResource(R.string.reports_chart_worksite_description, spoken),
            )
        }
    }
}

@Composable
private fun ShareRow(actions: ReportsActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = { actions.onShare(ShareTarget.ANY_APP) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.reports_share))
        }
        OutlinedButton(
            onClick = { actions.onShare(ShareTarget.EMAIL) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.Email, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.reports_email))
        }
    }
}

/** Placeholder sized like the stats and chart it stands in for, so nothing jumps on load. */
@Composable
private fun ReportSkeleton() {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag(SKELETON_TAG),
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(height = 76.dp, modifier = Modifier.weight(1f))
                SkeletonBlock(height = 76.dp, modifier = Modifier.weight(1f))
            }
        }
        SkeletonBlock(height = 264.dp)
    }
}

@Composable
private fun SkeletonBlock(height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
    )
}

const val REPORTS_LIST_TAG = "reports_list"
const val BIWEEKLY_CARD_TAG = "reports_biweekly_card"
const val ACTIVE_SHIFT_TAG = "reports_active_shift"
const val SKELETON_TAG = "reports_skeleton"

@Preview(showBackground = true)
@Composable
private fun ReportsPreview() {
    val today = LocalDate.of(2026, 9, 10)
    val period = ReportPeriod.containing(ReportPeriodType.WEEK, today)
    val hour = 3_600_000L
    val report = AttendanceReport(
        period = period,
        totalMillis = 30 * hour,
        days = period.days.mapIndexed { i, d -> DayTotal(d, if (i in 1..4) 7 * hour + i * 15 * 60_000 else 0) },
        worksites = listOf(
            WorksiteTotal("a", WorksiteLabel.Registered("Downtown Office"), 22 * hour),
            WorksiteTotal("b", WorksiteLabel.GeneralTimeclock, 8 * hour),
        ),
        shiftCount = 4,
        daysWorked = 4,
        averageMillisPerWorkedDay = 7 * hour + 30 * 60_000,
        longestShiftMillis = 9 * hour,
        averageClockIn = LocalTime.of(8, 4),
    )
    EmployeeAttendanceTheme {
        ReportsContent(
            selection = PeriodSelection(period, canGoNext = false),
            report = ReportSection.Ready(
                report,
                ActiveShiftNotice(WorksiteLabel.Registered("Downtown Office"), Instant.now()),
            ),
            biweekly = BiweeklySection.Ready(BiweeklySummary(report, 28 * hour)),
            actions = ReportsActions(),
        )
    }
}
