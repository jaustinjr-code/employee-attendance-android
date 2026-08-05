package com.jaustinjr.employeeattendance.attendance

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Notification *lifecycle* for one worksite (issue #23). Instrumented rather than unit, because the
 * behaviour under test is which cards Android is actually showing.
 *
 * Requires notifications to be enabled for the app (they are by default on a fresh test device, and
 * the POST_NOTIFICATIONS grant is in the test manifest on API 33+); the tests skip themselves rather
 * than fail if the platform is refusing to post at all.
 */
class ClockNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = ClockNotifier(context)
    private val worksite = WorkLocation("site-a", "Downtown Office", null, 37.0, -122.0, 150f)
    private val other = WorkLocation("site-b", "Warehouse", null, 37.8, -122.3, 150f)

    private fun activeFor(locationId: String) =
        manager.activeNotifications.filter { it.packageName == context.packageName }
            .count { it.id.isFor(locationId) }

    /** Mirrors ClockNotifier.notificationId, which is private. */
    private fun Int.isFor(locationId: String): Boolean =
        ClockType.entries.any { this == (locationId.hashCode() * 31 + it.ordinal) and 0x7FFFFFFF }

    @Before
    @After
    fun clearNotifications() {
        // activeNotifications needs API 23; the whole class is meaningless below it.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        manager.cancelAll()
    }

    @Test
    fun clockOutReplacesTheClockInCardForTheSameWorksite() {
        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_IN, 1_000L), withUndo = true)
        assumeTrue("notifications are not being posted on this device", activeFor("site-a") == 1)

        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_OUT, 2_000L), withUndo = true)

        // Exactly one live card per worksite, so there is never an ambiguous Undo to tap.
        assertEquals(1, activeFor("site-a"))
    }

    @Test
    fun clockInReplacesTheClockOutCardForTheSameWorksite() {
        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_OUT, 1_000L), withUndo = true)
        assumeTrue("notifications are not being posted on this device", activeFor("site-a") == 1)

        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_IN, 2_000L), withUndo = true)

        assertEquals(1, activeFor("site-a"))
    }

    @Test
    fun aDifferentWorksiteKeepsItsOwnCard() {
        notifier.notifyRecorded(worksite, event(ClockType.CLOCK_IN, 1_000L), withUndo = true)
        assumeTrue("notifications are not being posted on this device", activeFor("site-a") == 1)

        notifier.notifyRecorded(other, event(ClockType.CLOCK_IN, 2_000L, "site-b"), withUndo = true)

        // Replacement is per worksite: clocking into B must not silence A.
        assertEquals(1, activeFor("site-a"))
        assertEquals(1, activeFor("site-b"))
    }

    @Test
    fun cancelDismissesThePromptForThatWorksiteAndType() {
        notifier.notifyConfirm(worksite, ClockType.CLOCK_IN)
        assumeTrue("notifications are not being posted on this device", activeFor("site-a") == 1)

        notifier.cancel(worksite, ClockType.CLOCK_IN)

        assertTrue(activeFor("site-a") == 0)
    }

    private fun event(type: ClockType, epochMillis: Long, locationId: String = "site-a") =
        AttendanceEvent(locationId, type, epochMillis)
}
