package com.jaustinjr.employeeattendance.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.reporting.DayTotal
import com.jaustinjr.employeeattendance.reporting.ReportPeriodType
import com.jaustinjr.employeeattendance.reporting.WorksiteTotal
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModel
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import java.time.format.TextStyle
import java.util.Locale

private const val MILLIS_PER_HOUR = 3_600_000.0

/**
 * Hours worked per day as a column chart.
 *
 * The chart model is built with `remember(days)`: the ViewModel hands over the same [days] list
 * instance until the report actually changes, so recomposition for any other reason reuses the
 * model instead of rebuilding it.
 *
 * TalkBack reads [description] rather than the drawn bars.
 */
@Composable
fun DailyHoursChart(
    days: List<DayTotal>,
    periodType: ReportPeriodType,
    locale: Locale,
    description: String,
    modifier: Modifier = Modifier,
) {
    val model = remember(days) {
        CartesianChartModel(
            ColumnCartesianLayerModel.build { series(days.map { it.workedMillis / MILLIS_PER_HOUR }) },
        )
    }
    val labels = remember(days, periodType, locale) {
        days.map { day ->
            if (periodType == ReportPeriodType.WEEK) {
                day.date.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
            } else {
                day.date.dayOfMonth.toString()
            }
        }
    }
    val bottomFormatter = remember(labels) {
        CartesianValueFormatter { _, x, _ -> labels.getOrElse(x.toInt()) { "" } }
    }
    val startFormatter = remember { CartesianValueFormatter.decimal(decimalCount = 0, suffix = "h") }
    // A month has ~31 columns; thinner bars and every-7th label keep it readable at phone width.
    val isWeek = periodType == ReportPeriodType.WEEK
    val column = rememberLineComponent(
        fill = Fill(MaterialTheme.colorScheme.primary),
        thickness = if (isWeek) 20.dp else 6.dp,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
    )
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = ColumnCartesianLayer.ColumnProvider.series(column),
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = startFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = bottomFormatter,
                    itemPlacer = remember(isWeek) {
                        HorizontalAxis.ItemPlacer.aligned(spacing = { if (isWeek) 1 else 7 })
                    },
                ),
            ),
            model = model,
            // Fit the whole period on screen: a report is read at a glance, not scrubbed.
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp)
                .clearAndSetSemantics { contentDescription = description },
        )
    }
}

/** Colors for worksite slices, from the theme so they follow dark mode and dynamic color. */
@Composable
fun worksiteColors(): List<Color> = MaterialTheme.colorScheme.run {
    listOf(primary, tertiary, secondary, error, outline)
}

/**
 * Share of time by worksite as a donut chart, with a legend that carries the actual values, so the
 * chart is never the only way to read a number.
 */
@Composable
fun WorksiteShareChart(
    worksites: List<WorksiteTotal>,
    totalMillis: Long,
    worksiteName: (WorksiteTotal) -> String,
    duration: (Long) -> String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = worksiteColors()
    val model = remember(worksites) {
        PieChartModel.build(*worksites.map { it.workedMillis }.toTypedArray())
    }
    val slices = remember(colors) { colors.map { PieChart.Slice(fill = Fill(it)) } }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PieChartHost(
            chart = rememberPieChart(
                sliceProvider = remember(slices) { PieChart.SliceProvider.series(slices) },
                spacing = 2.dp,
                innerSize = PieSize.Inner.fixed(72.dp),
            ),
            model = model,
            modifier = Modifier
                .size(128.dp)
                .clearAndSetSemantics { contentDescription = description },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            worksites.forEachIndexed { index, total ->
                val percent = if (totalMillis == 0L) 0 else (total.workedMillis * 100 / totalMillis).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(colors[index % colors.size], CircleShape),
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            text = worksiteName(total),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.reports_worksite_value,
                                duration(total.workedMillis),
                                percent,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
