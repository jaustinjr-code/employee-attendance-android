package com.jaustinjr.employeeattendance.ui.reports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.jaustinjr.employeeattendance.reporting.ActiveShiftNotice
import com.jaustinjr.employeeattendance.reporting.AttendanceReport
import com.jaustinjr.employeeattendance.reporting.BiweeklySummary
import com.jaustinjr.employeeattendance.reporting.DayTotal
import com.jaustinjr.employeeattendance.reporting.ReportPeriod
import com.jaustinjr.employeeattendance.reporting.ReportPeriodType
import com.jaustinjr.employeeattendance.reporting.ShareTarget
import com.jaustinjr.employeeattendance.reporting.WorksiteLabel
import com.jaustinjr.employeeattendance.reporting.WorksiteTotal
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders the stateless Reports content; the charts are real Vico composables. */
class ReportsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val hour = 3_600_000L
    private val period = ReportPeriod.containing(ReportPeriodType.WEEK, LocalDate.of(2026, 9, 10))

    private fun report(total: Long = 30 * hour) = AttendanceReport(
        period = period,
        totalMillis = total,
        days = period.days.mapIndexed { i, d -> DayTotal(d, if (total > 0 && i in 1..4) total / 4 else 0) },
        worksites = if (total == 0L) {
            emptyList()
        } else {
            listOf(
                WorksiteTotal("a", WorksiteLabel.Registered("Downtown Office"), total * 3 / 4),
                WorksiteTotal("b", WorksiteLabel.GeneralTimeclock, total / 4),
            )
        },
        shiftCount = if (total == 0L) 0 else 4,
        daysWorked = if (total == 0L) 0 else 4,
        averageMillisPerWorkedDay = total / 4,
        longestShiftMillis = total / 4,
        averageClockIn = if (total == 0L) null else LocalTime.of(8, 0),
    )

    private fun setContent(
        report: ReportSection,
        biweekly: BiweeklySection = BiweeklySection.Ready(BiweeklySummary(report(), 28 * hour)),
        canGoNext: Boolean = false,
        actions: ReportsActions = ReportsActions(),
    ) {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                ReportsContent(
                    selection = PeriodSelection(period, canGoNext),
                    report = report,
                    biweekly = biweekly,
                    actions = actions,
                )
            }
        }
    }

    @Test
    fun showsTheBiweeklySummaryAndTheStats() {
        setContent(ReportSection.Ready(report(), activeShift = null))

        composeRule.onNodeWithTag(BIWEEKLY_CARD_TAG).assertIsDisplayed()
        // The same figures also appear in the stat tiles, so look inside the card.
        composeRule.onNode(hasText("30h 00m") and hasAnyAncestor(hasTestTag(BIWEEKLY_CARD_TAG))).assertIsDisplayed()
        composeRule.onNode(hasText("4 shifts") and hasAnyAncestor(hasTestTag(BIWEEKLY_CARD_TAG))).assertIsDisplayed()
        composeRule.onNodeWithText("Up 2h 00m from the two weeks before").assertIsDisplayed()
        composeRule.onNodeWithText("Most time at Downtown Office").assertIsDisplayed()
        composeRule.onNodeWithText("Total worked").assertIsDisplayed()
        composeRule.onNodeWithTag(REPORTS_LIST_TAG).performScrollToNode(hasText("Time by worksite"))
        composeRule.onNodeWithText("22h 30m · 75%").assertIsDisplayed()
    }

    @Test
    fun anActiveShiftIsCalledOut() {
        setContent(
            ReportSection.Ready(
                report(),
                ActiveShiftNotice(WorksiteLabel.Registered("Downtown Office"), Instant.now()),
            ),
        )

        composeRule.onNodeWithTag(REPORTS_LIST_TAG).performScrollToNode(hasText("clocked in", substring = true))
        composeRule.onNodeWithText("You're clocked in at Downtown Office", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("That shift isn't included until you clock out.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun anEmptyPeriodSaysSoAndDrawsNoCharts() {
        setContent(ReportSection.Ready(report(total = 0), activeShift = null))

        composeRule.onNodeWithText("No completed shifts in this period.").assertIsDisplayed()
        composeRule.onNodeWithText("Hours by day").assertDoesNotExist()
    }

    @Test
    fun loadingShowsASkeletonInsteadOfNumbers() {
        setContent(ReportSection.Loading, biweekly = BiweeklySection.Loading)

        composeRule.onNodeWithTag(SKELETON_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Total worked").assertDoesNotExist()
    }

    @Test
    fun nextIsDisabledOnTheCurrentPeriod() {
        setContent(ReportSection.Ready(report(), null), canGoNext = false)

        composeRule.onNodeWithContentDescription("Next period").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Previous period").assertIsEnabled()
    }

    @Test
    fun controlsReportTheirIntent() {
        val types = mutableListOf<ReportPeriodType>()
        val targets = mutableListOf<ShareTarget>()
        var previous = 0
        setContent(
            ReportSection.Ready(report(), null),
            actions = ReportsActions(
                onPeriodTypeSelected = { types += it },
                onPreviousPeriod = { previous++ },
                onShare = { targets += it },
            ),
        )

        composeRule.onNodeWithText("Month").performClick()
        composeRule.onNodeWithContentDescription("Previous period").performClick()
        composeRule.onNodeWithTag(REPORTS_LIST_TAG).performScrollToNode(hasText("Email"))
        composeRule.onNodeWithText("Share").performClick()
        composeRule.onNodeWithText("Email").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(ReportPeriodType.MONTH), types)
            assertEquals(1, previous)
            assertEquals(listOf(ShareTarget.ANY_APP, ShareTarget.EMAIL), targets)
        }
    }
}
