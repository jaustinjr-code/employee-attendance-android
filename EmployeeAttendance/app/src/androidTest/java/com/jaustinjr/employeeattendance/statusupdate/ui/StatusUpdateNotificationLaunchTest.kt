package com.jaustinjr.employeeattendance.statusupdate.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.MainActivity
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateIntents
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end coverage of the notification launch path: a real [MainActivity] cold start carrying
 * [StatusUpdateIntents]' extras, through `StartupGate`, `StatusUpdateOverlayViewModel`, and
 * [com.jaustinjr.employeeattendance.statusupdate.StatusUpdateCoordinator.claimNotificationRequest]
 * validating against the real [com.jaustinjr.employeeattendance.attendance.AttendanceRepository].
 *
 * Uses [createEmptyComposeRule] rather than `createAndroidComposeRule<MainActivity>()` because the
 * launch `Intent` (carrying the notification extras) has to be supplied to [ActivityScenario.launch]
 * directly; Compose's test roots auto-register regardless of how the Activity was started.
 *
 * There is deliberately no "system back dismisses the deck" case here: once the deck moved into a
 * real [androidx.compose.ui.window.Dialog] (its own window), neither `Espresso.pressBack()` (it
 * targets `onView(isRoot())`, ambiguous with a second window/root) nor an injected `KEYCODE_BACK`
 * (silently swallowed somewhere in `compose-ui-test`'s main-looper synchronization around a second
 * window) reliably reach it in this harness — confirmed manually on-device that a real back press
 * (`adb shell input keyevent 4`) correctly dismisses it. That behavior is covered deterministically
 * instead by [StatusUpdateCardStackTest.systemBack_dismissesTheDeck], which drives the composable
 * directly through `createComposeRule()`'s single root.
 */
@RunWith(AndroidJUnit4::class)
class StatusUpdateNotificationLaunchTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context =
        ApplicationProvider.getApplicationContext<EmployeeAttendanceApplication>()
    private val container get() = context.container

    private val locationId = "notif-site"
    private val clockOutAtMillis = 42_000L

    @Before
    @After
    fun resetSharedAppState() {
        // These are real, app-scoped singletons (see AppContainer) that outlive any one
        // ActivityScenario within this instrumentation process, so a leftover clock-out or saved
        // status update from an earlier test must not leak into this one.
        container.attendanceRepository.clearAll()
        container.statusUpdateRepository.clearAll()
        if (!container.statusUpdateSettingsStore.enabled.value) {
            container.statusUpdateSettingsStore.setEnabled(true)
        }
    }

    private fun notificationIntent(request: StatusUpdateRequest): Intent =
        StatusUpdateIntents.putExtras(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            request,
        )

    private fun deckIsShowing(): Boolean =
        composeRule.onAllNodesWithText("What did you do today?").fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits for `StartupGate` to actually open before touching anything gate-dependent: without
     * this, `forgedExtras_showNoDeck` in particular could observe "no deck" simply because nothing
     * has composed yet, not because `claimNotificationRequest` rejected the extras. Waits for
     * `startupComplete` itself, then for a node that only exists once the gated content is
     * composed (the Attendance destination's app-bar title), so a StateFlow flip that outraces
     * composition can't produce the same false pass.
     */
    private fun waitForStartupGateToOpen() {
        composeRule.waitUntil(timeoutMillis = 10_000) { context.startupComplete.value }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Attendance").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun launchFromNotification_forARealClockOut_opensTheDeckSavesOnCompletion_andDoesNotReopenOnRecreate() {
        val request = StatusUpdateRequest(locationId, clockOutAtMillis)
        container.attendanceRepository.recordClockOut(locationId, clockOutAtMillis)

        ActivityScenario.launch<MainActivity>(notificationIntent(request)).use { scenario ->
            waitForStartupGateToOpen()
            composeRule.waitUntil(timeoutMillis = 10_000) { deckIsShowing() }
            composeRule.onAllNodesWithText("What did you do today?").onFirst().assertIsDisplayed()

            composeRule.onNode(hasSetTextAction()).performTextInput("Wrote the report")
            composeRule.onNodeWithText("Next").performClick()
            composeRule.onNode(hasSetTextAction()).performTextInput("Ship the release")
            composeRule.onNodeWithText("Next").performClick()
            composeRule.onNode(hasSetTextAction()).performTextInput("Nothing blocked")
            composeRule.onNodeWithText("Done").performClick()

            composeRule.waitUntil(timeoutMillis = 10_000) {
                container.statusUpdateRepository.statusUpdates.value.isNotEmpty()
            }

            val saved = container.statusUpdateRepository.statusUpdates.value.single()
            assertEquals(request.clockOutId, saved.clockOutId)
            assertEquals("Wrote the report", saved.didToday)
            assertEquals("Ship the release", saved.plannedTomorrow)
            assertEquals("Nothing blocked", saved.couldNotDo)

            // The completed deck already closed itself; recreating (a config change) must not
            // reopen it — StatusUpdateIntents.consumeRequest stripped the extras on first read, and
            // MainActivity only re-reads them when savedInstanceState == null.
            scenario.recreate()
            composeRule.waitForIdle()
            assertEquals(false, deckIsShowing())

            // Still only the one saved update — recreation must not re-run completion either.
            assertEquals(1, container.statusUpdateRepository.statusUpdates.value.size)
        }
    }

    @Test
    fun forgedExtras_showNoDeck() {
        // No attendance event exists for this location/time, so claimNotificationRequest must
        // reject it — MainActivity is exported, so these extras are untrusted input.
        val forged = StatusUpdateRequest("no-such-location", 999_000L)

        ActivityScenario.launch<MainActivity>(notificationIntent(forged)).use {
            waitForStartupGateToOpen()
            assertEquals(false, deckIsShowing())
        }
    }
}
