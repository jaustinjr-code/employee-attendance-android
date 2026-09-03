package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for issue #58's gate.
 *
 * The property that matters is not "a spinner is visible" but that the gated content is **never
 * composed** while startup is incomplete. The real content composes `ViewModel` factories, and
 * those dereference `EncryptedSharedPreferences`-backed repositories out of the app container — on
 * a cold start that blocks the main thread on a `SYNCHRONIZED` `by lazy` monitor held by the
 * startup IO worker. A gate that merely hid the content would still run the factories and still
 * stall, so these tests count compositions rather than asserting on visibility.
 */
@RunWith(AndroidJUnit4::class)
class StartupGateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun contentIsNotComposedBeforeStartupCompletes() {
        val compositions = AtomicInteger(0)

        compose.setContent {
            StartupGate(started = false) {
                compositions.incrementAndGet()
                Text("gated content")
            }
        }

        compose.waitForIdle()
        assertEquals(
            "gated content was composed while startup was incomplete; " +
                "the ViewModel factories would have run and blocked the main thread",
            0,
            compositions.get(),
        )
    }

    @Test
    fun contentIsComposedOnceStartupCompletes() {
        val compositions = AtomicInteger(0)

        compose.setContent {
            StartupGate(started = true) {
                compositions.incrementAndGet()
                Text("gated content")
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("gated content").assertIsDisplayed()
        assertEquals(1, compositions.get())
    }

    @Test
    fun flippingToStartedComposesTheContentExactlyOnce() {
        // The realistic sequence: the gate opens partway through the Activity's life as
        // startupComplete flips. The content must appear, and must not be composed more than once
        // by the flip itself — a factory run twice would defeat the point of gating.
        val compositions = AtomicInteger(0)
        var started by mutableStateOf(false)

        compose.setContent {
            StartupGate(started) {
                compositions.incrementAndGet()
                Text("gated content")
            }
        }

        compose.waitForIdle()
        assertEquals(0, compositions.get())

        started = true
        compose.waitForIdle()

        compose.onNodeWithText("gated content").assertIsDisplayed()
        assertEquals("content composed more than once by the gate opening", 1, compositions.get())
    }
}
