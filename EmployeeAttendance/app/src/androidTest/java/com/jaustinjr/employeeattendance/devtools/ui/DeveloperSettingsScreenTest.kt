package com.jaustinjr.employeeattendance.devtools.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.devtools.PermissionOverride
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.tracking.TrackingStatus
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeveloperSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: DeveloperSettingsUiState,
        actions: DeveloperSettingsActions = DeveloperSettingsActions(),
    ) {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                DeveloperSettingsContent(state = state, actions = actions)
            }
        }
    }

    @Test
    fun showsTheLiveStateSummary() {
        setContent(
            DeveloperSettingsUiState(
                accessLevel = LocationAccessLevel.WHEN_IN_USE,
                isPrecise = true,
                trackingStatus = TrackingStatus.FOREGROUND_ONLY,
                proximity = ProximityState.INSIDE,
                activeWorksiteName = "Downtown Office",
            )
        )

        composeRule.onNodeWithText("Proximity: INSIDE").assertExists()
        composeRule.onNodeWithText("Active worksite: Downtown Office").assertExists()
        composeRule.onNodeWithText("Tracking: FOREGROUND_ONLY").assertExists()
    }

    @Test
    fun worksiteDependentActionsAreDisabledWithoutAnActiveWorksite() {
        setContent(DeveloperSettingsUiState(activeWorksiteName = null))

        composeRule.onNodeWithText("Simulate arrival").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Simulate departure").assertIsNotEnabled()
        composeRule.onNodeWithText("Fix at worksite").assertIsNotEnabled()
        // Actions that don't need one stay available, so the developer can always get to a worksite.
        composeRule.onNodeWithText("Seed sample worksite").performScrollTo().assertIsEnabled()
    }

    @Test
    fun worksiteDependentActionsAreEnabledOnceOneIsActive() {
        setContent(DeveloperSettingsUiState(activeWorksiteName = "Downtown Office"))

        composeRule.onNodeWithText("Simulate arrival").performScrollTo().assertIsEnabled()
    }

    @Test
    fun theSelectedPermissionOverrideIsReflected() {
        setContent(DeveloperSettingsUiState(permissionOverride = PermissionOverride.ALWAYS_PRECISE))

        composeRule.onNodeWithText("All the time · precise").performScrollTo().assertIsSelected()
    }

    @Test
    fun selectingAPermissionOverrideReportsIt() {
        var selected: PermissionOverride? = null
        setContent(
            DeveloperSettingsUiState(),
            DeveloperSettingsActions(onPermissionOverrideSelected = { selected = it }),
        )

        composeRule.onNodeWithText("Denied").performScrollTo().performClick()

        assertEquals(PermissionOverride.DENIED, selected)
    }

    @Test
    fun anActiveOverrideIsCalledOutSoTheStateIsNotMistakenForReal() {
        setContent(DeveloperSettingsUiState(permissionOverride = PermissionOverride.DENIED))

        composeRule
            .onNodeWithText("A developer override is in force", substring = true)
            .assertExists()
    }

    @Test
    fun simulatingAnArrivalInvokesTheAction() {
        var arrived = false
        setContent(
            DeveloperSettingsUiState(activeWorksiteName = "Downtown Office"),
            DeveloperSettingsActions(onSimulateArrival = { arrived = true }),
        )

        composeRule.onNodeWithText("Simulate arrival").performScrollTo().performClick()

        assertTrue(arrived)
    }

    @Test
    fun postingANotificationPassesTheRequestedVariant() {
        var request: Triple<ClockType, Boolean, Boolean>? = null
        setContent(
            DeveloperSettingsUiState(activeWorksiteName = "Downtown Office"),
            DeveloperSettingsActions(
                onPostNotification = { type, undo, confirm -> request = Triple(type, undo, confirm) },
            ),
        )

        composeRule.onNodeWithText("Confirm clock out").performScrollTo().performClick()

        assertEquals(Triple(ClockType.CLOCK_OUT, false, true), request)
    }

    @Test
    fun theExportButtonIsDisabledWhileAnExportIsRunning() {
        setContent(DeveloperSettingsUiState(isExportingLog = true))

        composeRule.onNodeWithText("Export log").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun resettingRequiresConfirmation() {
        var reset = false
        setContent(
            DeveloperSettingsUiState(),
            DeveloperSettingsActions(onResetDeveloperConfiguration = { reset = true }),
        )

        composeRule.onNodeWithText("Reset developer configuration").performScrollTo().performClick()

        // The dialog is up; nothing has happened yet.
        composeRule.onNodeWithText("Reset developer configuration?").assertExists()
        assertEquals(false, reset)

        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(false, reset)
    }

    @Test
    fun confirmingTheResetInvokesIt() {
        var reset = false
        setContent(
            DeveloperSettingsUiState(),
            DeveloperSettingsActions(onResetDeveloperConfiguration = { reset = true }),
        )

        composeRule.onNodeWithText("Reset developer configuration").performScrollTo().performClick()
        composeRule.onNodeWithText("Reset developer configuration?").assertExists()
        composeRule.onNodeWithText("Reset").performClick()

        assertTrue(reset)
    }
}
