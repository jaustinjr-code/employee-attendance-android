package com.jaustinjr.employeeattendance.devtools

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import com.jaustinjr.employeeattendance.Attendance
import com.jaustinjr.employeeattendance.DeveloperSettings
import com.jaustinjr.employeeattendance.ui.main.MainAppBar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Covers the hidden entry point end to end: the app bar's title tap wired to
 * [DevUnlockTapCounter] and on to the `DeveloperSettings` destination. The counter's own
 * timing rules are unit-tested; what needs a device is that the title is actually clickable and the
 * navigation lands.
 */
class DeveloperSettingsUnlockTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    private fun setContent(clickable: Boolean) {
        composeRule.setContent {
            navController = TestNavHostController(LocalContext.current)
            navController.navigatorProvider.addNavigator(ComposeNavigator())
            val counter = DevUnlockTapCounter()
            MainAppBar(
                title = "Attendance",
                onTitleClick = if (clickable) {
                    {
                        // A real monotonic clock would make the test timing-dependent; the counter's
                        // window behaviour is covered by DevUnlockTapCounterTest instead.
                        if (counter.onTap(0L)) navController.navigate(DeveloperSettings)
                    }
                } else {
                    null
                },
            )
            NavHost(navController, startDestination = Attendance) {
                composable<Attendance> { Text("Attendance screen") }
                composable<DeveloperSettings> { Text("Developer screen") }
            }
        }
    }

    private fun currentRoute(): String? =
        navController.currentBackStackEntry?.destination?.route

    @Test
    fun fiveTapsOnTheTitleOpenDeveloperSettings() {
        setContent(clickable = true)

        repeat(5) { composeRule.onNodeWithText("Attendance").performClick() }

        composeRule.runOnIdle {
            val route = currentRoute()
            assertTrue("route was $route", route?.contains("DeveloperSettings") == true)
        }
    }

    @Test
    fun fourTapsDoNotOpenIt() {
        setContent(clickable = true)

        repeat(4) { composeRule.onNodeWithText("Attendance").performClick() }

        composeRule.runOnIdle {
            val route = currentRoute()
            assertFalse("route was $route", route?.contains("DeveloperSettings") == true)
        }
    }

    @Test
    fun withoutAHandlerTheTitleIsInert() {
        // The release wiring: MainActivity passes null, so no amount of tapping does anything.
        setContent(clickable = false)

        repeat(8) { composeRule.onNodeWithText("Attendance").performClick() }

        composeRule.runOnIdle {
            val route = currentRoute()
            assertFalse("route was $route", route?.contains("DeveloperSettings") == true)
        }
    }
}
