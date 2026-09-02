package com.jaustinjr.employeeattendance.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.testing.TestNavHostController
import androidx.test.platform.app.InstrumentationRegistry
import com.jaustinjr.employeeattendance.Attendance
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.Settings
import com.jaustinjr.employeeattendance.Worksites
import org.junit.Rule
import org.junit.Test

/**
 * The app bar title must always agree with the destination the NavHost is rendering.
 *
 * NOTE ON COVERAGE: these tests drive navigation programmatically. They cannot reproduce the
 * original report's *dragged* predictive-back gesture — `BackEventCompat` progress can't be
 * injected through `createComposeRule`, and there is no device in CI to perform a real edge drag.
 * What they do pin down is the property whose absence caused the bug: the title is a function of
 * the current back stack entry, with no per-screen side effect that could win a race. The dragged
 * gesture itself is covered manually — see the PR's testing strategy.
 */
class AppBarTitleNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    private fun setUpNavigation() {
        composeRule.setContent {
            navController = TestNavHostController(LocalContext.current)
            navController.navigatorProvider.addNavigator(ComposeNavigator())

            // Mirrors MainActivity: the app bar is outside the NavHost and derives its title from
            // the back stack rather than from state the destinations push into it.
            val currentEntry by navController.currentBackStackEntryAsState()
            val title = stringResource(appBarTitleResFor(currentEntry?.destination?.route))

            Scaffold(topBar = { MainAppBar(title = title) }) { padding ->
                NavHost(
                    navController,
                    startDestination = Attendance,
                    modifier = Modifier.padding(padding),
                ) {
                    composable<Attendance> { Text("attendance-content") }
                    composable<Worksites> { Text("worksites-content") }
                    composable<Settings> { Text("settings-content") }
                }
            }
        }
    }

    @Test
    fun startDestination_showsItsOwnTitle() {
        setUpNavigation()

        composeRule.onNodeWithText(text(R.string.attendance_title)).assertIsDisplayed()
    }

    @Test
    fun navigatingForward_updatesTheTitle() {
        setUpNavigation()

        composeRule.runOnUiThread { navController.navigate(Worksites) }

        composeRule.onNodeWithText(text(R.string.worksites_title)).assertIsDisplayed()
        composeRule.onNodeWithText("worksites-content").assertIsDisplayed()
    }

    @Test
    fun poppingBack_restoresThePreviousTitle() {
        // The reported bug: after the back completes the content is Attendance but the header still
        // reads "Worksites".
        setUpNavigation()
        composeRule.runOnUiThread { navController.navigate(Worksites) }
        composeRule.onNodeWithText(text(R.string.worksites_title)).assertIsDisplayed()

        composeRule.runOnUiThread { navController.popBackStack() }

        composeRule.onNodeWithText("attendance-content").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.attendance_title)).assertIsDisplayed()
    }

    @Test
    fun poppingBackFromADeeperStack_showsTheDestinationActuallyRendered() {
        setUpNavigation()
        composeRule.runOnUiThread { navController.navigate(Worksites) }
        composeRule.runOnUiThread { navController.navigate(Settings) }
        composeRule.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()

        composeRule.runOnUiThread { navController.popBackStack() }

        composeRule.onNodeWithText("worksites-content").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.worksites_title)).assertIsDisplayed()
    }

    @Test
    fun titleSurvivesRepeatedRoundTrips() {
        // A push-based title only has to lose one race to go stale, and it stays stale afterwards.
        // Cycling repeatedly makes any leftover state visible.
        setUpNavigation()

        repeat(3) {
            composeRule.runOnUiThread { navController.navigate(Worksites) }
            composeRule.onNodeWithText(text(R.string.worksites_title)).assertIsDisplayed()

            composeRule.runOnUiThread { navController.popBackStack() }
            composeRule.onNodeWithText(text(R.string.attendance_title)).assertIsDisplayed()
        }
    }

    /** Resolves a title string the same way the app bar does, so the assertions read as UI text. */
    private fun text(@StringRes id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
