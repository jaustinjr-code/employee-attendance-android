package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import org.junit.Rule
import org.junit.Test

class LocationDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val office = WorkLocation(
        id = "downtown-office",
        name = "Downtown Office",
        address = "123 Market St",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    @Test
    fun always_showsNameMapAndClockIn() {
        composeRule.setContent {
            LocationDetailContent(
                state = LocationUiState(
                    activeWorkLocation = office,
                    proximity = ProximityState.INSIDE,
                    accessLevel = LocationAccessLevel.ALWAYS,
                    lastClockInEpochMillis = 1_716_552_000_000L,
                ),
            )
        }

        // The name appears both as the headline and on the map chip.
        composeRule.onAllNodesWithText("Downtown Office").onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Map preview of your work location").assertIsDisplayed()
        composeRule.onNodeWithText("Last clocked in:", substring = true).assertIsDisplayed()
    }

    @Test
    fun whenInUse_hidesMapAndShowsLockedNotice() {
        composeRule.setContent {
            LocationDetailContent(
                state = LocationUiState(
                    activeWorkLocation = office,
                    proximity = ProximityState.OUTSIDE,
                    accessLevel = LocationAccessLevel.WHEN_IN_USE,
                    lastClockInEpochMillis = null,
                ),
            )
        }

        composeRule.onNodeWithContentDescription("Map preview of your work location").assertDoesNotExist()
        composeRule.onNodeWithText("Allow all the time", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("No clock-ins recorded yet").assertIsDisplayed()
    }

    @Test
    fun noLocation_showsEmptyMessage() {
        composeRule.setContent {
            LocationDetailContent(state = LocationUiState(accessLevel = LocationAccessLevel.ALWAYS))
        }

        composeRule.onNodeWithText("No work location is set up yet.").assertIsDisplayed()
    }
}
