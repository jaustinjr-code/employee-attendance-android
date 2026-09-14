package com.jaustinjr.employeeattendance.statusupdate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.jaustinjr.employeeattendance.MainActivity
import com.jaustinjr.employeeattendance.R

/**
 * The notification surface [StatusUpdateCoordinator] talks to when a status update must be
 * surfaced without a screen visible. Extracted as an interface so the coordinator stays
 * unit-testable off-device; the production implementation is [StatusUpdateNotifier].
 */
interface StatusUpdateNotifications {
    /** Posts "a status update is waiting" for [request]. Tapping it opens [MainActivity]. */
    fun notifyPending(request: StatusUpdateRequest)

    /** Retracts the "waiting" card for [request], if it is still showing. */
    fun cancel(request: StatusUpdateRequest)
}

/**
 * Posts (and cancels) the "status update waiting" notification. Mirrors
 * [com.jaustinjr.employeeattendance.attendance.ClockNotifier]'s channel/permission handling: its
 * own channel (required on API 26+), and a best-effort post that degrades silently when
 * `POST_NOTIFICATIONS` (API 33+) is not granted, same as every other notification in this app —
 * posting is never a blocking gate on the underlying action. Per the no-resurfacing requirement, a
 * missed post simply means that day's status update is skipped, the same outcome as a dismissal.
 */
class StatusUpdateNotifier(context: Context) : StatusUpdateNotifications {

    private val appContext = context.applicationContext

    override fun notifyPending(request: StatusUpdateRequest) {
        ensureChannel()
        val id = notificationIdFor(request.clockOutId)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.status_update_notification_title))
            .setContentText(appContext.getString(R.string.status_update_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(request, id))
            .build()
        post(id, builder)
    }

    override fun cancel(request: StatusUpdateRequest) {
        NotificationManagerCompat.from(appContext).cancel(notificationIdFor(request.clockOutId))
    }

    private fun post(id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) {
            Log.d(TAG, "notifications disabled; skipping post")
            return
        }
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on 13+. This is the notification path (no in-app
            // prompt exists here), so a blocked post is never seen; per no-resurfacing, that day's
            // status update is simply skipped, same as any other dismissal.
            Log.w(TAG, "notify blocked (missing POST_NOTIFICATIONS)", e)
        }
    }

    private fun contentIntent(request: StatusUpdateRequest, requestCode: Int): PendingIntent {
        val intent = StatusUpdateIntents.putExtras(
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            request,
        )
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.status_update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "StatusUpdateNotifier"
        private const val CHANNEL_ID = "status_update"

        /**
         * Stable per-clock-out id so a later post for the same clock-out replaces rather than
         * stacks. Exposed for tests that need to identify this app's card in the shade.
         */
        @VisibleForTesting
        fun notificationIdFor(clockOutId: String): Int = clockOutId.hashCode() and 0x7FFFFFFF
    }
}
