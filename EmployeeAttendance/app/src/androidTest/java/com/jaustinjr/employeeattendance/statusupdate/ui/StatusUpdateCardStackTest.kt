package com.jaustinjr.employeeattendance.statusupdate.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.espresso.Espresso
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for the stateless [StatusUpdateCardStack]. Drives it through a small stateful
 * test harness — the same shape a real caller ([StatusUpdateOverlayHost]) uses — so advancing by
 * button and by swipe, retaining drafts, and stopping at the first card are all exercised against
 * real recomposition, not a mock.
 */
class StatusUpdateCardStackTest {

    @get:Rule
    val composeRule = createComposeRule()

    // The question's heading Text carries a contentDescription rather than a Text/EditableText
    // property (see QuestionCard), so onNodeWithText only ever matches the answer field's label —
    // onAllNodesWithText(...).onFirst() is used defensively rather than onNodeWithText so this
    // helper still works if that changes.
    private fun questionNode(text: String) = composeRule.onAllNodesWithText(text).onFirst()

    private fun setContent(onDone: (List<String>) -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                var drafts by remember { mutableStateOf(listOf("", "", "")) }
                var index by remember { mutableStateOf(0) }
                StatusUpdateCardStack(
                    state = StatusUpdateCardStackUiState(drafts = drafts, currentIndex = index),
                    onDraftChanged = { i, value ->
                        drafts = drafts.toMutableList().also { it[i] = value }
                    },
                    onForward = {
                        if (index == StatusUpdateCardStackUiState.CARD_COUNT - 1) {
                            onDone(drafts)
                        } else {
                            index++
                        }
                    },
                    onBack = { if (index > 0) index-- },
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun firstCard_showsFirstQuestionAndHidesBack() {
        setContent()

        questionNode("What did you do today?").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun next_advancesThroughAllThreeCards() {
        setContent()

        composeRule.onNodeWithText("Next").performClick()
        questionNode("What's planned for tomorrow?").assertIsDisplayed()

        composeRule.onNodeWithText("Next").performClick()
        questionNode("What couldn't be done?").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun back_returnsToPreviousCardAndStopsAtFirst() {
        setContent()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Back").performClick()
        questionNode("What did you do today?").assertIsDisplayed()

        // No Back affordance on the first card; nothing crashes and the card is unchanged.
        questionNode("What did you do today?").assertIsDisplayed()
    }

    @Test
    fun swipeLeft_advancesCard() {
        setContent()

        composeRule.onNodeWithTag(StatusUpdateTestTags.CARD).performTouchInput { swipeLeft() }

        questionNode("What's planned for tomorrow?").assertIsDisplayed()
    }

    @Test
    fun swipeRight_onFirstCard_isNoOp() {
        setContent()

        composeRule.onNodeWithTag(StatusUpdateTestTags.CARD).performTouchInput { swipeRight() }

        questionNode("What did you do today?").assertIsDisplayed()
    }

    @Test
    fun swipeRight_afterAdvancing_goesBack() {
        setContent()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithTag(StatusUpdateTestTags.CARD).performTouchInput { swipeRight() }

        questionNode("What did you do today?").assertIsDisplayed()
    }

    @Test
    fun typedText_survivesNextAndBack() {
        setContent()

        composeRule.onNode(hasSetTextAction()).performTextInput("Finished the report")

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Back").performClick()

        composeRule.onNodeWithText("Finished the report").assertIsDisplayed()
    }

    @Test
    fun typedText_survivesSwipingAwayAndBack() {
        setContent()

        composeRule.onNode(hasSetTextAction()).performTextInput("Finished the report")

        composeRule.onNodeWithTag(StatusUpdateTestTags.CARD).performTouchInput { swipeLeft() }
        // The flip transition takes ~300ms (two 150ms legs); let it finish before swiping again.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(StatusUpdateTestTags.CARD).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Finished the report").assertIsDisplayed()
    }

    @Test
    fun completingLastCard_savesOnce() {
        var savedDrafts: List<String>? = null
        setContent(onDone = { savedDrafts = it })

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.runOnIdle {
            assertTrue(savedDrafts != null)
        }
    }

    /**
     * The visual question (QuestionCard's heading Text) carries its own controlled semantics
     * (`clearAndSetSemantics`): a heading, a *Polite* live region, and a `contentDescription` —
     * not a `Text`/`EditableText` property. That's what keeps it from showing up as a second
     * `onNodeWithText` match against the answer field's identical `label`, i.e. TalkBack does not
     * land on two back-to-back nodes reading the same words.
     */
    @Test
    fun questionHeading_announcesOnceAsAHeadingLiveRegion_distinctFromTheFieldLabel() {
        setContent()

        val headingMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit) and
            hasContentDescriptionExactly("What did you do today?")
        composeRule.onNode(headingMatcher).assertExists()
        composeRule.onNode(headingMatcher).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )

        // Only the field's label carries the question as matchable `Text` — the heading does not
        // contribute a second match.
        assertEquals(
            1,
            composeRule.onAllNodesWithText("What did you do today?").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun systemBack_dismissesTheDeck() {
        var dismissed = false
        setContent(onDismiss = { dismissed = true })

        Espresso.pressBack()

        composeRule.runOnIdle {
            assertTrue(dismissed)
        }
    }
}
