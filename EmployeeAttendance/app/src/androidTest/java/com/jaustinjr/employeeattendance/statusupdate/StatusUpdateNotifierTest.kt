package com.jaustinjr.employeeattendance.statusupdate

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Modeled on `ClockNotifierTest`: instrumented rather than unit, because what is under test is
 * which cards Android is actually showing — there is no seam below [StatusUpdateNotifications] to
 * fake this.
 */
class StatusUpdateNotifierTest {

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = StatusUpdateNotifier(context)
    private val request = StatusUpdateRequest("site-a", 1_000L)

    @Before
    @After
    fun clearNotifications() {
        manager.cancelAll()
        awaitCount(request, 0)
    }

    @Test
    fun notifyPendingPostsACard() {
        notifier.notifyPending(request)

        awaitCount(request, 1)
    }

    @Test
    fun aSecondPostForTheSameClockOutReplacesTheFirst() {
        notifier.notifyPending(request)
        awaitCount(request, 1)

        notifier.notifyPending(request)

        awaitCount(request, 1)
    }

    @Test
    fun cancelDismissesTheCard() {
        notifier.notifyPending(request)
        awaitCount(request, 1)

        notifier.cancel(request)

        awaitCount(request, 0)
    }

    private fun awaitCount(request: StatusUpdateRequest, expected: Int) {
        val id = StatusUpdateNotifier.notificationIdFor(request.clockOutId)
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var actual = activeCount(id)
        while (actual != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MILLIS)
            actual = activeCount(id)
        }
        assertEquals("live cards for ${request.clockOutId}", expected, actual)
    }

    private fun activeCount(id: Int): Int =
        manager.activeNotifications
            .filter { it.packageName == context.packageName }
            .count { it.id == id }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
