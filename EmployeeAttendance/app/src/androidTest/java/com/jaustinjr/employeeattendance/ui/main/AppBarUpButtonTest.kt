package com.jaustinjr.employeeattendance.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * The app bar's navigation slot holds exactly one affordance, chosen by where the user is:
 * an up button on any child of home, and the account affordance once home is reached.
 *
 * Attendance is the root, so up is a single pop — it lands on the destination the user came from,
 * which from a deep stack is another child rather than home.
 */
class AppBarUpButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    private fun setUpNavigation() {
        composeRule.setContent {
            // remember: the controller must survive recomposition. Building a fresh one each pass
            // hands the NavHost a new instance every time, which recomposes it again — the test
            // then never reaches idle.
            val context = LocalContext.current
            navController = remember {
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            }

            // Mirrors MainActivity: both the title and the navigation icon are derived from the
            // back stack, so neither can disagree with the destination being rendered.
            val currentEntry by navController.currentBackStackEntryAsState()
            val currentRoute = currentEntry?.destination?.route

            Scaffold(
                topBar = {
                    MainAppBar(
                        title = stringResource(appBarTitleResFor(currentRoute)),
                        showUpButton = isChildDestination(currentRoute),
                        onNavigateUp = { navController.popBackStack() },
                    )
                }
            ) { padding ->
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
    fun home_showsTheAccountAffordanceAndNoUpButton() {
        setUpNavigation()

        composeRule.onNodeWithContentDescription(text(R.string.cd_account)).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(text(R.string.cd_navigate_back))
            .assertCountEquals(0)
    }

    @Test
    fun aChildDestination_swapsTheAccountAffordanceForAnUpButton() {
        setUpNavigation()

        composeRule.runOnUiThread { navController.navigate(Worksites) }

        composeRule.onNodeWithContentDescription(text(R.string.cd_navigate_back))
            .assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(text(R.string.cd_account))
            .assertCountEquals(0)
    }

    @Test
    fun up_returnsToTheDestinationTheUserCameFrom() {
        setUpNavigation()
        composeRule.runOnUiThread { navController.navigate(Worksites) }

        composeRule.onNodeWithContentDescription(text(R.string.cd_navigate_back)).performClick()

        composeRule.onNodeWithText("attendance-content").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.attendance_title)).assertIsDisplayed()
    }

    @Test
    fun up_fromADeepStackGoesOneLevel_notHome() {
        // The requirement that makes this more than a popBackStack alias: from Settings reached via
        // Worksites, up must land on Worksites, and only the next press reaches home.
        setUpNavigation()
        composeRule.runOnUiThread { navController.navigate(Worksites) }
        composeRule.runOnUiThread { navController.navigate(Settings) }

        composeRule.onNodeWithContentDescription(text(R.string.cd_navigate_back)).performClick()

        composeRule.onNodeWithText("worksites-content").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text(R.string.cd_navigate_back))
            .assertIsDisplayed()
    }

    @Test
    fun reachingHome_bringsTheAccountAffordanceBack() {
        setUpNavigation()
        composeRule.runOnUiThread { navController.navigate(Worksites) }
        composeRule.runOnUiThread { navController.navigate(Settings) }

        composeRule.onNodeWithContentDescription(text(R.string.cd_navigate_back)).performClick()
        composeRule.onNodeWithContentDescription(text(R.string.cd_navigate_back)).performClick()

        composeRule.onNodeWithText("attendance-content").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text(R.string.cd_account)).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(text(R.string.cd_navigate_back))
            .assertCountEquals(0)
    }

    /** Resolves a string the same way the app bar does, so assertions read as UI text. */
    private fun text(@StringRes id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
