package com.jaustinjr.employeeattendance.attendance

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Notification *lifecycle* for one worksite (issue #23): posting a card for a worksite must retire
 * the opposite-direction one, so there is never more than one live Undo per worksite.
 *
 * Instrumented rather than unit, because the behaviour under test is which cards Android is
 * actually showing — there is no seam below [ClockNotifications] to fake. That also means this file
 * is **not** executable in CI or in the environment it was written in (no device); it is written to
 * be run on real hardware, and until it has been, the manual testing steps in the PR are the actual
 * verification for this half of the fix.
 *
 * [notify] and [cancel] are asynchronous one-way calls into system_server, so every read of the
 * shade polls rather than sampling once — otherwise a real regression could surface as a timing
 * flake instead of a failure.
 */
class ClockNotifierTest {

    /**
     * POST_NOTIFICATIONS is a runtime permission from API 33 and `targetSdk` is 36, so without this
     * grant `ClockNotifier.post()` bails at `areNotificationsEnabled()` and every assertion below
     * would be vacuous. There is no androidTest manifest in this project; the grant comes from here.
     */
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = ClockNotifier(context)
    private val worksite = WorkLocation("site-a", "Downtown Office", null, 37.0, -122.0, 150f)
    private val other = WorkLocation("site-b", "Warehouse", null, 37.8, -122.3, 150f)

    @Before
    @After
    fun clearNotifications() {
        manager.cancelAll()
        awaitCount("site-a", 0)
        awaitCount("site-b", 0)
    }

    @Test
    fun clockOutReplacesTheClockInCardForTheSameWorksite() {
        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_IN, 1_000L), withUndo = true)
        awaitCount("site-a", 1)

        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_OUT, 2_000L), withUndo = true)

        // Exactly one live card per worksite, so there is never an ambiguous Undo to tap.
        // Before the fix this settles at 2: the ids differ by clock type, so neither replaces the other.
        awaitCount("site-a", 1)
    }

    @Test
    fun clockInReplacesTheClockOutCardForTheSameWorksite() {
        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_OUT, 1_000L), withUndo = true)
        awaitCount("site-a", 1)

        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_IN, 2_000L), withUndo = true)

        awaitCount("site-a", 1)
    }

    @Test
    fun aDifferentWorksiteKeepsItsOwnCard() {
        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_IN, 1_000L), withUndo = true)
        awaitCount("site-a", 1)

        notifier.notifyRecorded(other, event(ClockType.CLOCK_IN, 2_000L, "site-b"), withUndo = true)

        // Replacement is per worksite: clocking into B must not silence A.
        awaitCount("site-a", 1)
        awaitCount("site-b", 1)
    }

    @Test
    fun cancelDismissesThePromptForThatWorksiteAndType() {
        notifier.notifyConfirm(worksite, ClockType.CLOCK_IN)
        awaitCount("site-a", 1)

        notifier.cancel(worksite, ClockType.CLOCK_IN)

        awaitCount("site-a", 0)
    }

    /** Fails with the observed count if the shade has not settled on [expected] within the timeout. */
    private fun awaitCount(locationId: String, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var actual = activeFor(locationId)
        while (actual != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MILLIS)
            actual = activeFor(locationId)
        }
        assertEquals("live cards for $locationId", expected, actual)
    }

    /** How many of this app's clock cards — either direction — are showing for [locationId]. */
    private fun activeFor(locationId: String): Int {
        val ids = ClockType.entries
            .map { ClockNotifier.notificationIdFor(locationId, it) }
            .toSet()
        return manager.activeNotifications
            .filter { it.packageName == context.packageName }
            .count { it.id in ids }
    }

    private fun event(type: ClockType, epochMillis: Long, locationId: String = "site-a") =
        AttendanceEvent(locationId, type, epochMillis)

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
