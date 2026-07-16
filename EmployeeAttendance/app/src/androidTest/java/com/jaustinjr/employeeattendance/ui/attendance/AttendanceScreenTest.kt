package com.jaustinjr.employeeattendance.ui.attendance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.ui.LocationUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AttendanceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val office = WorkLocation(
        id = "downtown-office",
        name = "Downtown Office",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    @Test
    fun showsSetupChip_whenNotGranted() {
        composeRule.setContent {
            AttendanceScreen(
                todayDate = "Sunday, May 24",
                locationState = LocationUiState(accessLevel = LocationAccessLevel.NONE),
            )
        }

        composeRule.onNodeWithText("Set Up Location").assertIsDisplayed()
    }

    @Test
    fun showsPill_whenSetUp() {
        composeRule.setContent {
            AttendanceScreen(
                todayDate = "Sunday, May 24",
                locationState = LocationUiState(
                    activeWorkLocation = office,
                    accessLevel = LocationAccessLevel.ALWAYS,
                ),
            )
        }

        composeRule.onNodeWithText("Downtown Office").assertIsDisplayed()
        composeRule.onNodeWithText("Set Up Location").assertDoesNotExist()
    }

    @Test
    fun chipClick_invokesSetupCallback() {
        var clicked = false
        composeRule.setContent {
            AttendanceScreen(
                todayDate = "Sunday, May 24",
                locationState = LocationUiState(accessLevel = LocationAccessLevel.NONE),
                onLocationSetupClick = { clicked = true },
            )
        }

        composeRule.onNodeWithText("Set Up Location").performClick()

        assertTrue(clicked)
    }

    @Test
    fun pillClick_invokesDetailCallback() {
        var opened = false
        composeRule.setContent {
            AttendanceScreen(
                todayDate = "Sunday, May 24",
                locationState = LocationUiState(
                    activeWorkLocation = office,
                    accessLevel = LocationAccessLevel.ALWAYS,
                ),
                onLocationPillClick = { opened = true },
            )
        }

        composeRule.onNodeWithText("Downtown Office").performClick()

        assertTrue(opened)
    }

    @Test
    fun clockIn_invokesClockInCallback() {
        var clockedIn = false
        composeRule.setContent {
            AttendanceScreen(
                todayDate = "Sunday, May 24",
                locationState = LocationUiState(
                    activeWorkLocation = office,
                    accessLevel = LocationAccessLevel.ALWAYS,
                ),
                onClockIn = { clockedIn = true },
            )
        }

        composeRule.onNodeWithText("Clock in").performClick()

        assertTrue(clockedIn)
    }
}
