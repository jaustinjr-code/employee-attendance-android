package com.jaustinjr.employeeattendance.statusupdate.history.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.jaustinjr.employeeattendance.ui.main.UpInterceptor
import com.jaustinjr.employeeattendance.ui.main.appBarTitleResFor
import com.jaustinjr.employeeattendance.ui.main.isChildDestination
import com.jaustinjr.employeeattendance.ui.main.performUp
import com.jaustinjr.employeeattendance.ui.main.updateInterceptor
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The read-only → edit → save / discard flow, hosted the way MainActivity hosts it: real
 * navigation, the real app bar, and [performUp] — the same function `MainActivity` calls from its
 * app bar — so a change to the real up-button rule is caught here instead of by a second,
 * hand-copied implementation that could silently drift from it.
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
                // Mirrors MainActivity's upInterceptor: null everywhere except while the editor is
                // current, which registers its own discard-confirmation interceptor.
                var upInterceptor by remember { mutableStateOf<UpInterceptor?>(null) }
                Scaffold(
                    topBar = {
                        MainAppBar(
                            title = stringResource(appBarTitleResFor(route)),
                            showUpButton = isChildDestination(route),
                            onNavigateUp = { performUp(upInterceptor, navController) },
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
                                onInterceptUpChanged = { entryId, onUp ->
                                    upInterceptor = updateInterceptor(upInterceptor, entryId, onUp)
                                },
                                upEntryId = backStackEntry.id,
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
    fun theAppBarUpButton_asksBeforeLeaving_evenImmediatelyAfterOpening() {
        setContent()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Edit").performClick()
        // One frame is enough for the editor to compose and run its registration effect. This is
        // deliberately far short of NavHost's ~700ms default enter-transition duration — i.e. short
        // of when a registration gated on the destination reaching RESUMED (the earlier, incomplete
        // fix for the Major up-button finding) would have registered at all. Before this fix, up
        // pressed in this window silently popped back to the read-only screen instead of asking;
        // this is the regression test for that gap, confirmed to fail against the RESUMED-gated
        // version and pass against the id-matching one below.
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.mainClock.autoAdvance = true

        assertDiscardDialogShown()
    }

    @Test
    fun reopeningTheEditorWhileThePreviousInstanceIsStillExiting_keepsTheNewInstanceGuarded() {
        openEditor()
        composeRule.mainClock.autoAdvance = false

        // Discard without letting the pop settle, so the departing editor's DisposableEffect
        // onDispose — deferred until its own exit transition finishes — is still in flight.
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Discard").performClick()
        // Just enough frames for the pop to settle and the read-only screen's Edit button to
        // appear — empirically the minimum (3 frames) at which it does, chosen deliberately small
        // so the reopen below still lands inside the departing editor's own exit-transition window.
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }

        // Reopen immediately, inside that same window. Each Edit tap creates a fresh
        // NavBackStackEntry (and so a fresh interceptor entryId) even though the route is
        // unchanged. Advancing well past that (60 frames, ~1s of virtual time) before touching up
        // is deliberate: logging the registration events during this test's development confirmed
        // the departing editor's delayed onDispose(null) — from its own exit transition, unrelated
        // to the reopen — does not actually fire until dozens of frames later, well after this
        // reopened editor has already registered. Advancing far enough to let that stale onDispose
        // land is what makes this the real regression case: the reopened editor's registration must
        // survive it, which is exactly what updateInterceptor's id check (and not a raw
        // `upInterceptor = it` assignment) guarantees.
        composeRule.onNodeWithText("Edit").performClick()
        repeat(60) { composeRule.mainClock.advanceTimeByFrame() }

        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.mainClock.autoAdvance = true

        assertDiscardDialogShown()
    }

    @Test
    fun theAppBarUpButton_discard_closesTheDialogOneFrameBeforeNavigatingAway() {
        openEditor()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertDiscardDialogShown()

        // Frame-precise regression test for the dialog-stays-visible-behind-navigation bug — see
        // "Hosting a flow that needs the real up interceptor" in testing.md for why a plain
        // assertDoesNotExist() right after the click can't prove this, and what the one-frame
        // advance below actually demonstrates.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Discard changes?").assertDoesNotExist()
        composeRule.onNodeWithText("Edit status update").assertIsDisplayed()

        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithText("Status update").assertIsDisplayed()
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
        composeRule.onNodeWithText("Discard changes?").assertDoesNotExist()
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
