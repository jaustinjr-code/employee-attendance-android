package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocationSetupChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun none_showsSetUpLabel() {
        composeRule.setContent {
            LocationSetupChip(accessLevel = LocationAccessLevel.NONE, onClick = {})
        }
        composeRule.onNodeWithText("Set Up Location").assertIsDisplayed()
    }

    @Test
    fun whenInUse_showsLimitedLabel() {
        composeRule.setContent {
            LocationSetupChip(accessLevel = LocationAccessLevel.WHEN_IN_USE, onClick = {})
        }
        composeRule.onNodeWithText("Limited Location Access").assertIsDisplayed()
    }

    @Test
    fun always_showsLocationOnLabel() {
        composeRule.setContent {
            LocationSetupChip(accessLevel = LocationAccessLevel.ALWAYS, onClick = {})
        }
        composeRule.onNodeWithText("Location On").assertIsDisplayed()
    }

    @Test
    fun click_invokesCallback() {
        var clicked = false
        composeRule.setContent {
            LocationSetupChip(accessLevel = LocationAccessLevel.NONE, onClick = { clicked = true })
        }
        composeRule.onNodeWithText("Set Up Location").performClick()
        assertTrue(clicked)
    }
}
