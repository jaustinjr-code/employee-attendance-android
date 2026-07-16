package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocationPermissionDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTitleBodyAndButtons() {
        composeRule.setContent {
            LocationPermissionRationaleDialog(
                title = "Enable Auto Clock-In?",
                description = "We can clock you in automatically.",
                confirmLabel = "Enable Location Services",
                dismissLabel = "Maybe Later",
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Enable Auto Clock-In?").assertIsDisplayed()
        composeRule.onNodeWithText("We can clock you in automatically.").assertIsDisplayed()
        composeRule.onNodeWithText("Enable Location Services").assertIsDisplayed()
        composeRule.onNodeWithText("Maybe Later").assertIsDisplayed()
    }

    @Test
    fun confirm_invokesConfirmCallback() {
        var confirmed = false
        composeRule.setContent {
            LocationPermissionRationaleDialog(
                title = "T",
                description = "D",
                confirmLabel = "Enable Location Services",
                dismissLabel = "Maybe Later",
                onConfirm = { confirmed = true },
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Enable Location Services").performClick()

        assertTrue(confirmed)
    }

    @Test
    fun dismiss_invokesDismissCallback() {
        var dismissed = false
        composeRule.setContent {
            LocationPermissionRationaleDialog(
                title = "T",
                description = "D",
                confirmLabel = "Enable Location Services",
                dismissLabel = "Maybe Later",
                onConfirm = {},
                onDismiss = { dismissed = true },
            )
        }

        composeRule.onNodeWithText("Maybe Later").performClick()

        assertTrue(dismissed)
    }
}
