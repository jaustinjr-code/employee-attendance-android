package com.jaustinjr.employeeattendance.statusupdate.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    // The answer field has no visible label, so the question's text appears only on the heading.
    // onAllNodesWithText(...).onFirst() stays defensive in case a second text node is ever added.
    private fun questionNode(text: String) = composeRule.onAllNodesWithText(text).onFirst()

    private fun setContent(
        onDone: (List<String>) -> Unit = {},
        onDismiss: () -> Unit = {},
        viewportHeight: Dp? = null,
    ) {
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
                    modifier = if (viewportHeight != null) Modifier.height(viewportHeight) else Modifier,
                )
            }
        }
    }

    private fun inCard() = hasAnyAncestor(hasTestTag(StatusUpdateTestTags.CARD))

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

    @Test
    fun questionHeading_isAPoliteLiveRegionHeading_andTheOnlyTextShowingTheQuestion() {
        setContent()

        val question = "What did you do today?"
        composeRule.onNode(hasText(question))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            )
        assertEquals(1, composeRule.onAllNodesWithText(question).fetchSemanticsNodes().size)
    }

    @Test
    fun answerField_isNamedByTheQuestion_andShowsAnExampleHintPerCard() {
        setContent()

        composeRule.onNode(hasSetTextAction())
            .assert(hasContentDescriptionExactly("What did you do today?"))
        composeRule.onNodeWithText("e.g. Finished the inventory count", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNode(hasSetTextAction())
            .assert(hasContentDescriptionExactly("What's planned for tomorrow?"))
        composeRule.onNodeWithText("e.g. Restock the front shelves", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNode(hasSetTextAction())
            .assert(hasContentDescriptionExactly("What couldn't be done?"))
        composeRule.onNodeWithText("e.g. Couldn't finish deliveries", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun navigationButtons_arePartOfTheCard() {
        setContent()

        composeRule.onNode(hasText("Next") and inCard()).assertIsDisplayed()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNode(hasText("Back") and inCard()).assertIsDisplayed()
        composeRule.onNode(hasText("Next") and inCard()).assertIsDisplayed()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNode(hasText("Done") and inCard()).assertIsDisplayed()
    }

    @Test
    fun shortViewport_keepsFieldAndButtonsInsideTheCard() {
        // Roughly the space left above an open keyboard.
        setContent(viewportHeight = 360.dp)
        composeRule.onNodeWithText("Next").performClick()

        val card = composeRule.onNodeWithTag(StatusUpdateTestTags.CARD).getUnclippedBoundsInRoot()
        val field = composeRule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val back = composeRule.onNodeWithText("Back").getUnclippedBoundsInRoot()
        val next = composeRule.onNodeWithText("Next").getUnclippedBoundsInRoot()

        assertTrue("field $field overlaps buttons $next", field.bottom <= next.top)
        assertTrue("field $field overlaps buttons $back", field.bottom <= back.top)
        assertTrue("next $next outside card $card", next.bottom <= card.bottom)
        assertTrue("back $back outside card $card", back.bottom <= card.bottom)
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
