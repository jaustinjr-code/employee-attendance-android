package com.jaustinjr.employeeattendance.statusupdate.history.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.espresso.Espresso
import com.jaustinjr.employeeattendance.StatusUpdateDetail
import com.jaustinjr.employeeattendance.StatusUpdateEdit
import com.jaustinjr.employeeattendance.statusupdate.DefaultStatusUpdateRepository
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdate
import com.jaustinjr.employeeattendance.statusupdate.history.CLOCK_OUT_ID_ARG
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateDetailViewModel
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateEditViewModel
import com.jaustinjr.employeeattendance.ui.main.MainAppBar
import com.jaustinjr.employeeattendance.ui.main.appBarTitleResFor
import com.jaustinjr.employeeattendance.ui.main.isChildDestination
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The read-only → edit → save / discard flow, hosted the way MainActivity hosts it: real navigation,
 * the real app bar, and up dispatched as a back press so the editor's discard confirmation catches it.
 */
class StatusUpdateEditFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val id = "site-a@1000"
    private val repository = DefaultStatusUpdateRepository().apply {
        save(
            StatusUpdate(
                clockOutId = id,
                didToday = "Wrote the report",
                plannedTomorrow = "",
                couldNotDo = "",
                completedAtMillis = 2_000L,
                clockOutAtMillis = 1_000L,
            ),
        )
    }

    private fun setContent() {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                val navController = rememberNavController()
                val entry by navController.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                Scaffold(
                    topBar = {
                        MainAppBar(
                            title = stringResource(appBarTitleResFor(route)),
                            showUpButton = isChildDestination(route),
                            onNavigateUp = {
                                composeRule.activity.onBackPressedDispatcher.onBackPressed()
                            },
                        )
                    },
                ) { padding ->
                    NavHost(
                        navController,
                        startDestination = StatusUpdateDetail(id),
                        modifier = Modifier.padding(padding),
                    ) {
                        composable<StatusUpdateDetail> { backStackEntry ->
                            val clockOutId = backStackEntry.toRoute<StatusUpdateDetail>().clockOutId
                            StatusUpdateDetailScreen(
                                onEdit = { navController.navigate(StatusUpdateEdit(clockOutId)) },
                                viewModel = remember {
                                    StatusUpdateDetailViewModel(
                                        SavedStateHandle(mapOf(CLOCK_OUT_ID_ARG to clockOutId)),
                                        repository,
                                    )
                                },
                            )
                        }
                        composable<StatusUpdateEdit> { backStackEntry ->
                            val clockOutId = backStackEntry.toRoute<StatusUpdateEdit>().clockOutId
                            StatusUpdateEditScreen(
                                onSaved = { navController.popBackStack() },
                                onExit = { navController.popBackStack() },
                                viewModel = remember {
                                    StatusUpdateEditViewModel(
                                        SavedStateHandle(mapOf(CLOCK_OUT_ID_ARG to clockOutId)),
                                        repository,
                                        clock = { 1_757_896_260_000 },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openEditor() {
        setContent()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Edit status update").assertIsDisplayed()
    }

    private fun assertDiscardDialogShown() {
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
    }

    @Test
    fun detail_isReadOnlyAndShowsOnlyAnsweredQuestions() {
        setContent()

        composeRule.onNodeWithText("Wrote the report").assertIsDisplayed()
        composeRule.onNodeWithText("What did you do today?").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("What's planned for tomorrow?").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Save").fetchSemanticsNodes().size)
    }

    @Test
    fun cancel_withNoTextChanged_stillAsksBeforeLeaving() {
        openEditor()

        composeRule.onNodeWithText("Cancel").performClick()

        assertDiscardDialogShown()
    }

    @Test
    fun systemBack_asksBeforeLeaving() {
        openEditor()

        Espresso.pressBack()

        assertDiscardDialogShown()
    }

    @Test
    fun theAppBarUpButton_asksBeforeLeaving() {
        openEditor()

        composeRule.onNodeWithContentDescription("Navigate back").performClick()

        assertDiscardDialogShown()
    }

    @Test
    fun keepEditing_staysInTheEditorWithTheDrafts() {
        openEditor()
        composeRule.onNode(hasSetTextAction() and hasText("Wrote the report")).performTextReplacement("Draft")
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Keep editing").performClick()

        composeRule.onNodeWithText("Edit status update").assertIsDisplayed()
        composeRule.onNodeWithText("Draft").assertIsDisplayed()
    }

    @Test
    fun discard_returnsToTheReadOnlyScreenWithTheOriginalText() {
        openEditor()
        composeRule.onNode(hasSetTextAction() and hasText("Wrote the report")).performTextReplacement("Draft")
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Discard").performClick()

        composeRule.onNodeWithText("Status update").assertIsDisplayed()
        composeRule.onNodeWithText("Wrote the report").assertIsDisplayed()
        assertEquals("Wrote the report", repository.statusUpdates.value.single().didToday)
    }

    @Test
    fun save_returnsToTheReadOnlyScreenShowingTheEdits() {
        openEditor()
        composeRule.onNode(hasSetTextAction() and hasText("Wrote the report"))
            .performTextReplacement("Rewrote the report")

        composeRule.onNodeWithText("Save").performClick()

        composeRule.onNodeWithText("Status update").assertIsDisplayed()
        composeRule.onNodeWithText("Rewrote the report").assertIsDisplayed()
        composeRule.onNodeWithText("Edited", substring = true).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Discard changes?").fetchSemanticsNodes().size)
    }

    @Test
    fun clearingEveryAnswer_disablesSave() {
        openEditor()

        composeRule.onNode(hasSetTextAction() and hasText("Wrote the report")).performTextReplacement("")

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Fill in at least one answer to save.").assertIsDisplayed()
    }
}
