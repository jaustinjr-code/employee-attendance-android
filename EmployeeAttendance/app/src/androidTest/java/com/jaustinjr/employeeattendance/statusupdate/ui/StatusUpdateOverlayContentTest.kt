package com.jaustinjr.employeeattendance.statusupdate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateRequest
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test

@Serializable
private object TestHome

@Serializable
private object TestSettings

/**
 * Verifies [StatusUpdateOverlayContent] renders over whatever destination is current, without
 * disturbing it — the property MainActivity relies on by mounting the overlay as a Box sibling of
 * the NavHost rather than as a destination itself.
 */
class StatusUpdateOverlayContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deckRendersOverANonHomeDestination_andDestinationStaysUnderneath() {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                val navController = TestNavHostController(LocalContext.current)
                navController.navigatorProvider.addNavigator(ComposeNavigator())

                val overlayState = StatusUpdateOverlayUiState(
                    deck = StatusUpdateCardStackUiState(
                        drafts = listOf("", "", ""),
                        currentIndex = 0,
                    ),
                )

                Box(Modifier.fillMaxSize()) {
                    NavHost(navController, startDestination = TestHome) {
                        composable<TestHome> { Text("Home content") }
                        composable<TestSettings> { Text("Settings content") }
                    }
                    StatusUpdateOverlayContent(
                        state = overlayState,
                        onBeginPrompt = {},
                        onDismissPrompt = {},
                        onDraftChanged = { _, _ -> },
                        onForward = {},
                        onBack = {},
                        onDismissDeck = {},
                    )
                }

                LaunchedEffect(Unit) {
                    navController.navigate(TestSettings)
                }
            }
        }

        // The deck is on top of the Settings destination navigated to underneath it. The question
        // text is matched via `onAllNodesWithText` — it appears twice (heading + answer field
        // label; see QuestionCard) — an exact onNodeWithText match would be ambiguous.
        composeRule.onAllNodesWithText("What did you do today?").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Settings content").assertIsDisplayed()
    }

    @Test
    fun promptRendersWithoutDisturbingTheUnderlyingDestination() {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("Attendance content")
                    StatusUpdateOverlayContent(
                        state = StatusUpdateOverlayUiState(
                            prompt = StatusUpdateRequest(
                                locationId = "site-1",
                                clockOutAtMillis = 1_000L,
                            ),
                        ),
                        onBeginPrompt = {},
                        onDismissPrompt = {},
                        onDraftChanged = { _, _ -> },
                        onForward = {},
                        onBack = {},
                        onDismissDeck = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Attendance content").assertIsDisplayed()
        composeRule.onNodeWithText("Start status update?").assertIsDisplayed()
    }

    /**
     * `compose-ui-test`'s `performClick()`/`performTouchInput` dispatch a synthetic touch
     * directly to the target node's own root (see `AndroidActions.performClickImpl` ->
     * `AndroidInputDispatcher`), not through the real OS input pipeline — so it cannot exercise
     * genuine cross-window touch arbitration between the deck's Dialog and whatever is
     * underneath; a `performClick()` on the underneath node would "succeed" in-test regardless of
     * whether a real Dialog window covers it on a device. What *can* be verified in-process is the
     * structural fact that actually provides the blocking, focus containment, and TalkBack
     * isolation at runtime: the deck renders inside a real platform [isDialog] node, in a
     * different root than the content underneath it.
     */
    @Test
    fun deckOpen_rendersInsideARealDialog_separateFromTheContentUnderneath() {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        text = "Underneath button",
                        modifier = Modifier.clickable { },
                    )
                    StatusUpdateOverlayContent(
                        state = StatusUpdateOverlayUiState(
                            deck = StatusUpdateCardStackUiState(
                                drafts = listOf("", "", ""),
                                currentIndex = 0,
                            ),
                        ),
                        onBeginPrompt = {},
                        onDismissPrompt = {},
                        onDraftChanged = { _, _ -> },
                        onForward = {},
                        onBack = {},
                        onDismissDeck = {},
                    )
                }
            }
        }

        composeRule
            .onAllNodes(hasAnyAncestor(isDialog()) and hasText("What did you do today?"))
            .onFirst()
            .assertIsDisplayed()
        composeRule
            .onNode(!hasAnyAncestor(isDialog()) and hasText("Underneath button"))
            .assertIsDisplayed()
    }
}
