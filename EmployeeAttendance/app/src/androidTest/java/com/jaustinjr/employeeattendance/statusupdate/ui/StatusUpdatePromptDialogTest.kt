package com.jaustinjr.employeeattendance.statusupdate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StatusUpdatePromptDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTitleAndBothActions() {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                StatusUpdatePromptDialog(onBegin = {}, onNotNow = {})
            }
        }

        composeRule.onNodeWithText("Start status update?").assertIsDisplayed()
        composeRule.onNodeWithText("Begin").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test
    fun begin_invokesOnBegin() {
        var begun = false
        composeRule.setContent {
            EmployeeAttendanceTheme {
                StatusUpdatePromptDialog(onBegin = { begun = true }, onNotNow = {})
            }
        }

        composeRule.onNodeWithText("Begin").performClick()

        assertTrue(begun)
    }

    @Test
    fun notNow_invokesOnNotNow() {
        var dismissed = false
        composeRule.setContent {
            EmployeeAttendanceTheme {
                StatusUpdatePromptDialog(onBegin = {}, onNotNow = { dismissed = true })
            }
        }

        composeRule.onNodeWithText("Not now").performClick()

        assertTrue(dismissed)
    }
}
