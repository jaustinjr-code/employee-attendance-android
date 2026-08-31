package com.jaustinjr.employeeattendance.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.ui.DegradedNotice
import com.jaustinjr.employeeattendance.location.ui.LocationPill
import com.jaustinjr.employeeattendance.location.ui.LocationSetupChip
import com.jaustinjr.employeeattendance.location.ui.ProximityStatusRow
import org.junit.Rule
import org.junit.Test

/**
 * Golden-image coverage for the small location widgets. These are the pieces whose *appearance*
 * carries meaning — the setup chip's icon and label change with the granted access level, and the
 * proximity row's icon and tint change with proximity — so a silent visual regression here would
 * mislead the user without failing any behavioural assertion.
 *
 * Every composable covered is stateless and time-independent, which is what makes these captures
 * reproducible. See [Screenshots] for the recording workflow.
 */
class LocationComponentScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun locationPill() {
        composeRule.captureAndAssert("location-pill") {
            LocationPill(
                locationName = "Downtown Office",
                onClick = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    @Test
    fun locationPill_dark() {
        composeRule.captureAndAssert("location-pill-dark", darkTheme = true) {
            LocationPill(
                locationName = "Downtown Office",
                onClick = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    /**
     * All three access levels in one capture, so a change to any icon or label is caught and the
     * three states stay visually comparable in review.
     */
    @Test
    fun setupChip_allAccessLevels() {
        composeRule.captureAndAssert("setup-chip-all-levels") {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LocationSetupChip(accessLevel = LocationAccessLevel.NONE, onClick = {})
                LocationSetupChip(accessLevel = LocationAccessLevel.WHEN_IN_USE, onClick = {})
                LocationSetupChip(accessLevel = LocationAccessLevel.ALWAYS, onClick = {})
            }
        }
    }

    @Test
    fun proximityStatusRow_allStates() {
        composeRule.captureAndAssert("proximity-status-all-states") {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProximityStatusRow(proximity = ProximityState.INSIDE)
                ProximityStatusRow(proximity = ProximityState.OUTSIDE)
                ProximityStatusRow(proximity = ProximityState.UNKNOWN)
            }
        }
    }

    @Test
    fun degradedNotice() {
        composeRule.captureAndAssert("degraded-notice") {
            DegradedNotice(modifier = Modifier.padding(16.dp))
        }
    }
}
