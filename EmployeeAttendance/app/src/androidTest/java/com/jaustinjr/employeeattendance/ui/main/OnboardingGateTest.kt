package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Modeled on [StartupGateTest]. What matters is that the app is **never composed** behind the
 * carousel — composing it would construct the location ViewModels and raise the permission prompt
 * over onboarding — so these count compositions rather than asserting on visibility.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingGateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appIsNotComposedWhileOnboardingIsShown() {
        val compositions = AtomicInteger(0)

        compose.setContent {
            OnboardingGate(
                showOnboarding = true,
                onboarding = { Text("onboarding") },
            ) {
                compositions.incrementAndGet()
                Text("app")
            }
        }

        compose.onNodeWithText("onboarding").assertIsDisplayed()
        assertEquals("app was composed behind onboarding", 0, compositions.get())
    }

    @Test
    fun aReturningUserLandsOnTheAppWithoutOnboarding() {
        compose.setContent {
            OnboardingGate(
                showOnboarding = false,
                onboarding = { Text("onboarding") },
            ) {
                Text("app")
            }
        }

        compose.onNodeWithText("app").assertIsDisplayed()
        compose.onNodeWithText("onboarding").assertDoesNotExist()
    }

    @Test
    fun finishingOnboardingSwitchesStraightToTheApp() {
        // No transition: the frame after onboarding finishes is the app, with the carousel already
        // gone. An animated hand-off let the window background flash through between the two.
        val compositions = AtomicInteger(0)
        var showOnboarding by mutableStateOf(true)

        compose.setContent {
            OnboardingGate(
                showOnboarding = showOnboarding,
                onboarding = { Text("onboarding") },
            ) {
                compositions.incrementAndGet()
                Text("app")
            }
        }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        showOnboarding = false
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("app").assertIsDisplayed()
        compose.onNodeWithText("onboarding").assertDoesNotExist()
        assertEquals("app composed more than once by the switch", 1, compositions.get())
        compose.mainClock.autoAdvance = true
    }
}
