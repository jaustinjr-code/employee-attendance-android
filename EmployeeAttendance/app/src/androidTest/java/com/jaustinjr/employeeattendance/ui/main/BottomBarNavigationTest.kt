package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.testing.TestNavHostController
import com.jaustinjr.employeeattendance.Attendance
import com.jaustinjr.employeeattendance.Reports
import com.jaustinjr.employeeattendance.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The bottom bar as MainActivity wires it: Attendance is home, tabs keep their state across
 * switches, and the bar stays visible on child screens.
 */
class BottomBarNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    private fun setUp() {
        composeRule.setContent {
            val context = LocalContext.current
            navController = remember {
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            }
            val entry by navController.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            Scaffold(
                bottomBar = {
                    MainBottomBar(
                        currentRoute = route,
                        onSelect = { destination ->
                            navController.selectTab(
                                tab = destination,
                                route = if (destination == AppNavGraph.Reports) Reports else Attendance,
                                currentRoute = route,
                            )
                        },
                    )
                },
            ) { padding ->
                NavHost(navController, startDestination = Attendance, modifier = Modifier.padding(padding)) {
                    composable<Attendance> {
                        Column {
                            Text("attendance-content")
                            Button(onClick = { navController.navigate(Settings) }) { Text("open-settings") }
                        }
                    }
                    composable<Reports> {
                        // Stands in for a tab's own state (and, by the same mechanism, its
                        // ViewModel), which must survive switching away and back.
                        var taps by rememberSaveable { mutableIntStateOf(0) }
                        Button(onClick = { taps++ }) { Text("reports-taps-$taps") }
                    }
                    composable<Settings> { Text("settings-content") }
                }
            }
        }
    }

    @Test
    fun attendanceIsHomeAndItsTabIsSelected() {
        setUp()

        composeRule.onNodeWithText("attendance-content").assertIsDisplayed()
        composeRule.onNode(hasText("Attendance") and hasSelectableTab()).assertIsSelected()
    }

    @Test
    fun theReportsTabKeepsItsStateAcrossTabSwitches() {
        setUp()

        composeRule.onNodeWithText("Reports").performClick()
        composeRule.onNodeWithText("reports-taps-0").performClick()
        composeRule.onNodeWithText("reports-taps-1").assertIsDisplayed()

        composeRule.onNodeWithText("Attendance").performClick()
        composeRule.onNodeWithText("attendance-content").assertIsDisplayed()

        composeRule.onNodeWithText("Reports").performClick()
        composeRule.onNodeWithText("reports-taps-1").assertIsDisplayed()
    }

    @Test
    fun switchingTabsNeverStacksTabsOnTheBackStack() {
        setUp()

        repeat(3) {
            composeRule.onNodeWithText("Reports").performClick()
            composeRule.onNodeWithText("Attendance").performClick()
        }
        composeRule.onNodeWithText("Reports").performClick()

        composeRule.runOnIdle {
            // Attendance (start) and Reports: back from Reports lands on home, then exits.
            val tabs = navController.currentBackStack.value
                .mapNotNull { it.destination.route }
                .filter { it == AttendanceRoute || it == ReportsRoute }
            assertEquals(listOf(AttendanceRoute, ReportsRoute), tabs)
        }
    }

    @Test
    fun aChildScreenKeepsTheBottomBarAndItsParentTabSelected() {
        setUp()

        composeRule.onNodeWithText("open-settings").performClick()

        composeRule.onNodeWithText("settings-content").assertIsDisplayed()
        composeRule.onNode(hasText("Attendance") and hasSelectableTab()).assertIsSelected()
        composeRule.onNodeWithText("Reports").assertIsDisplayed()
    }

    @Test
    fun reportsIsReachableFromAChildScreen() {
        setUp()

        composeRule.onNodeWithText("open-settings").performClick()
        composeRule.onNodeWithText("Reports").performClick()

        composeRule.onNode(hasText("reports-taps-0", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("Reports") and hasSelectableTab()).assertIsSelected()
    }

    @Test
    fun tappingTheCurrentTabFromAChildScreenReturnsToItsRoot() {
        setUp()

        composeRule.onNodeWithText("open-settings").performClick()
        composeRule.onNode(hasText("Attendance") and hasSelectableTab()).performClick()

        composeRule.onNodeWithText("attendance-content").assertIsDisplayed()
    }

    private fun hasSelectableTab() = androidx.compose.ui.test.isSelectable()
}
