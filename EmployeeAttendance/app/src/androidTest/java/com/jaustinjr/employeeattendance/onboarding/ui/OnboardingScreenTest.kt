package com.jaustinjr.employeeattendance.onboarding.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The carousel's two ways forward — the button and a swipe — and the finish on the last page. */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val finishes = AtomicInteger(0)

    private fun setScreen() {
        compose.setContent {
            EmployeeAttendanceTheme {
                OnboardingScreen(onFinished = { finishes.incrementAndGet() })
            }
        }
    }

    @Test
    fun startsOnTheAttendancePage() {
        setScreen()

        compose.onNodeWithText(text(R.string.onboarding_attendance_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.onboarding_page_indicator, 1, 3))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_next)).assertIsDisplayed()
    }

    @Test
    fun nextWalksThroughEveryPageThenFinishes() {
        setScreen()

        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_worksite_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.onboarding_page_indicator, 2, 3))
            .assertIsDisplayed()

        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_reporting_title)).assertIsDisplayed()
        assertEquals("finished before the last page's button was pressed", 0, finishes.get())

        compose.onNodeWithText(text(R.string.onboarding_get_started)).performClick()
        compose.waitForIdle()
        assertEquals(1, finishes.get())
    }

    @Test
    fun swipingAdvancesTheCarousel() {
        setScreen()

        compose.onNodeWithTag(ONBOARDING_PAGER_TAG).performTouchInput { swipeLeft() }
        compose.onNodeWithText(text(R.string.onboarding_worksite_title)).assertIsDisplayed()

        compose.onNodeWithTag(ONBOARDING_PAGER_TAG).performTouchInput { swipeLeft() }
        compose.onNodeWithText(text(R.string.onboarding_reporting_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_get_started)).assertIsDisplayed()
        assertEquals("a swipe alone must not finish onboarding", 0, finishes.get())
    }
}
