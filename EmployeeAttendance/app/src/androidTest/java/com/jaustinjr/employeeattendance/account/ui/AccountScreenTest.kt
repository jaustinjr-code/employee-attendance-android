package com.jaustinjr.employeeattendance.account.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdate
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateQuestion
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateDay
import com.jaustinjr.employeeattendance.statusupdate.history.groupStatusUpdatesByDay
import com.jaustinjr.employeeattendance.statusupdate.history.ui.StatusUpdateHistoryTestTags
import com.jaustinjr.employeeattendance.ui.main.MainAppBar
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val updates = listOf(
        StatusUpdate(
            clockOutId = "site-a@1000",
            answers = mapOf(
                StatusUpdateQuestion.DID_TODAY to "Finished the inventory count",
                StatusUpdateQuestion.PLANNED_TOMORROW to "Restock the front shelves",
            ),
            completedAtMillis = 1_757_896_300_000,
            clockOutAtMillis = 1_757_896_260_000,
            clockInAtMillis = 1_757_866_920_000,
            worksiteName = "Main office",
        ),
    )

    private fun days(): List<StatusUpdateDay> =
        groupStatusUpdatesByDay(updates, TimeZone.getDefault())

    private fun setContent(
        days: List<StatusUpdateDay> = days(),
        onOpen: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                var name by remember { mutableStateOf("Jordan") }
                AccountContent(
                    displayName = name,
                    onDisplayNameChanged = { name = it },
                    historyDays = days,
                    onOpenStatusUpdate = onOpen,
                )
            }
        }
    }

    private fun shiftRow() =
        composeRule.onNodeWithTag(StatusUpdateHistoryTestTags.shift("site-a@1000"))

    @Test
    fun nameSectionComesBeforeTheHistorySection() {
        setContent()

        val name = composeRule.onNodeWithText("Display name").getUnclippedBoundsInRoot()
        val history = composeRule.onNodeWithText("Status updates").getUnclippedBoundsInRoot()
        assertTrue("name $name should be above history $history", name.bottom <= history.top)
    }

    @Test
    fun editingTheName_writesThrough() {
        setContent()

        composeRule.onNodeWithText("Jordan").performTextReplacement("Sam")

        composeRule.onNodeWithText("Sam").assertIsDisplayed()
    }

    @Test
    fun emptyHistory_explainsWhereUpdatesComeFrom() {
        setContent(days = emptyList())

        composeRule.onNodeWithText("Status updates you fill in", substring = true).assertIsDisplayed()
    }

    @Test
    fun aShift_showsItsWorksiteAPreviewAndHowManyMoreAnswers() {
        setContent()

        shiftRow().assertIsDisplayed()
        composeRule.onNodeWithText("Main office").assertIsDisplayed()
        composeRule.onNodeWithText("Finished the inventory count").assertIsDisplayed()
        composeRule.onNodeWithText("+1 more answer").assertIsDisplayed()
    }

    @Test
    fun tappingAShift_opensIt() {
        var opened: String? = null
        setContent(onOpen = { opened = it })

        shiftRow().performClick()

        composeRule.runOnIdle { assertEquals("site-a@1000", opened) }
    }

    @Test
    fun holdingAShift_peeksUntilReleased_withoutOpeningIt() {
        var opened: String? = null
        setContent(onOpen = { opened = it })

        shiftRow().performTouchInput { down(center) }
        // The long-press timeout runs on the test clock, which a held finger does not advance.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        // assertExists, not assertIsDisplayed: the peek is a non-focusable popup window, which the
        // test framework does not report as displayed while a finger is injected into the root below.
        // docs/maintenance/testing.md §6 describes the on-device check that it really draws.
        composeRule.onNodeWithTag(StatusUpdateHistoryTestTags.PEEK).assertExists()
        composeRule.onNodeWithText("Restock the front shelves").assertExists()

        shiftRow().performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithTag(StatusUpdateHistoryTestTags.PEEK).fetchSemanticsNodes().size)
        composeRule.runOnIdle { assertEquals(null, opened) }
    }

    @Test
    fun theAccessibilityLongClickAction_peeksUntilDismissed() {
        setContent()

        shiftRow().performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(StatusUpdateHistoryTestTags.PEEK).assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithTag(StatusUpdateHistoryTestTags.PEEK).fetchSemanticsNodes().size)
    }

    @Test
    fun theAppBarAccountButton_callsOnOpenAccount() {
        var opened = false
        composeRule.setContent {
            EmployeeAttendanceTheme {
                MainAppBar(title = "Attendance", onOpenAccount = { opened = true })
            }
        }

        composeRule.onNodeWithContentDescription("Account").performClick()

        composeRule.runOnIdle { assertTrue(opened) }
    }
}
