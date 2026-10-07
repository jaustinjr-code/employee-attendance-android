package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun finishingOnboardingTransitionsToTheAppAndRemovesTheCarousel() {
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
        compose.mainClock.advanceTimeBy(100)
        // Mid-transition both are composed: the carousel is animating out as the app comes in.
        compose.onNodeWithText("onboarding").assertExists()
        compose.onNodeWithText("app").assertExists()

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithText("app").assertIsDisplayed()
        compose.onNodeWithText("onboarding").assertDoesNotExist()
        assertEquals("app composed more than once by the transition", 1, compositions.get())
    }

    @Test
    fun noFrameOfTheTransitionShowsWhatIsBehindIt() {
        // Regression: the first version cross-faded the two screens with nothing painted behind
        // them, so for ~200 ms the window's (white) background flashed through. Both
        // stand-ins here are dark; a frame where anything light shows means something is uncovered.
        // Sampled at the centre and at the edge, where a scaling-in screen leaves a margin.
        var showOnboarding by mutableStateOf(true)
        compose.setContent {
            OnboardingGate(
                showOnboarding = showOnboarding,
                onboarding = { Box(Modifier.fillMaxSize().background(Color(0xFF202020))) },
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF303030)))
            }
        }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        showOnboarding = false
        compose.mainClock.advanceTimeByFrame()
        for (elapsed in 0..TRANSITION_SAMPLE_MILLIS step SAMPLE_STEP_MILLIS) {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val samples = listOf(
                pixels[pixels.width / 2, pixels.height / 2],
                pixels[EDGE_INSET_PX, pixels.height / 2],
            )
            samples.forEach { pixel ->
                assertTrue(
                    "frame at ${elapsed}ms shows a light pixel ($pixel): the window is uncovered",
                    pixel.red < MAX_DARK_CHANNEL && pixel.green < MAX_DARK_CHANNEL,
                )
            }
            compose.mainClock.advanceTimeBy(SAMPLE_STEP_MILLIS.toLong())
        }
        compose.mainClock.autoAdvance = true
    }

    private companion object {
        /** The whole cross-fade, plus a little after it. */
        const val TRANSITION_SAMPLE_MILLIS = AppNavTransitions.DURATION_MILLIS + 100
        const val SAMPLE_STEP_MILLIS = 25
        const val EDGE_INSET_PX = 4

        /** Both stand-ins are at most 0x30 per channel; anything well above that is bleed-through. */
        const val MAX_DARK_CHANNEL = 0.4f
    }
}
