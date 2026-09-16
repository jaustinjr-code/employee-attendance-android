package com.jaustinjr.employeeattendance.ui.reports

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.MainActivity
import org.junit.Rule
import org.junit.Test

/** The real MainActivity: the notification's intent lands on Reports, a plain launch on Attendance. */
class ReportsDeepLinkTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theNotificationIntentOpensTheReportsTab() {
        // ActivityScenario manages the task itself, so drop the NEW_TASK/CLEAR_TASK flags.
        val intent = Intent(MainActivity.openReportsIntent(context)).setFlags(0)

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasTestTag(BIWEEKLY_CARD_TAG)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(BIWEEKLY_CARD_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun aPlainLaunchOpensAttendanceAndTheBarReachesReports() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasText("Reports")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(BIWEEKLY_CARD_TAG).assertDoesNotExist()

            composeRule.onNodeWithText("Reports").performClick()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasTestTag(BIWEEKLY_CARD_TAG)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
