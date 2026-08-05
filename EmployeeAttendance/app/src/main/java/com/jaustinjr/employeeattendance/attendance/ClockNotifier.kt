package com.jaustinjr.employeeattendance.attendance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.registration.WorkLocation

/**
 * The notification surface the clock strategies talk to. Extracted as an interface so strategies
 * (and the auto-clock controller) can be unit-tested without the Android framework; the production
 * implementation is [ClockNotifier].
 */
interface ClockNotifications {
    /**
     * Posts a "clocked in/out" notification for [event], optionally with an Undo action.
     *
     * The whole [AttendanceEvent] is taken (not just its type) so the Undo action can name the exact
     * event it reverses; see [AttendanceRepository.undoEvent].
     */
    fun notifyRecorded(worksite: WorkLocation, event: AttendanceEvent, withUndo: Boolean)

    /** Posts an "arrived/left — confirm?" prompt with a Confirm action. */
    fun notifyConfirm(worksite: WorkLocation, clockType: ClockType)

    /**
     * Dismisses any live notification for [worksite] of [clockType]. Used to retract a prompt whose
     * action has become impossible — e.g. an unanswered "Arrived — clock in?" once the user has
     * already left the radius, which would otherwise open a session at a worksite they are no
     * longer at.
     */
    fun cancel(worksite: WorkLocation, clockType: ClockType)
}

/**
 * Posts (and cancels) the user-facing notifications for automatic clock-in/out. Strategies decide
 * *whether* and *which* to post; this class owns the Android plumbing (channel, actions, ids) so the
 * strategies stay free of framework details.
 *
 * The channel setup mirrors [com.jaustinjr.employeeattendance.location.tracking.LocationTrackingService].
 * On Android 13+ posting also needs the runtime POST_NOTIFICATIONS grant; [NotificationManagerCompat]
 * no-ops gracefully when it is absent, so a missing grant degrades to a silent record rather than a
 * crash.
 */
class ClockNotifier(context: Context) : ClockNotifications {

    private val appContext = context.applicationContext

    /** Posts "Clocked in/out" with an optional Undo action. Used by the immediate-record strategies. */
    override fun notifyRecorded(worksite: WorkLocation, event: AttendanceEvent, withUndo: Boolean) {
        val clockType = event.type
        val id = notificationId(worksite.id, clockType)
        val title = appContext.getString(
            when (clockType) {
                ClockType.CLOCK_IN -> R.string.clock_notification_clocked_in_title
                ClockType.CLOCK_OUT -> R.string.clock_notification_clocked_out_title
            },
            worksite.name,
        )
        val builder = baseBuilder(title)
        if (withUndo) {
            builder.addAction(
                0,
                appContext.getString(R.string.clock_notification_undo),
                pendingIntent(
                    action = ClockActionReceiver.ACTION_UNDO,
                    locationId = worksite.id,
                    clockType = clockType,
                    notificationId = id,
                    epochMillis = event.epochMillis,
                ),
            )
        }
        replacePrevious(worksite, clockType)
        post(id, builder)
    }

    /** Posts an "arrived/left — confirm?" prompt with a Confirm action. Used by the confirm strategy. */
    override fun notifyConfirm(worksite: WorkLocation, clockType: ClockType) {
        val id = notificationId(worksite.id, clockType)
        val title = appContext.getString(
            when (clockType) {
                ClockType.CLOCK_IN -> R.string.clock_notification_confirm_in_title
                ClockType.CLOCK_OUT -> R.string.clock_notification_confirm_out_title
            },
            worksite.name,
        )
        val builder = baseBuilder(title).addAction(
            0,
            appContext.getString(R.string.clock_notification_confirm),
            pendingIntent(ClockActionReceiver.ACTION_CONFIRM, worksite.id, clockType, id),
        )
        replacePrevious(worksite, clockType)
        post(id, builder)
    }

    /**
     * Dismisses the opposite-direction card for [worksite] before posting the [clockType] one.
     *
     * Notification ids are per (worksite, type), so Android's own replace-by-id never fires between
     * a clock-in and the clock-out that ends it: both cards would sit in the shade at once, each
     * with its own live Undo. Retiring the previous one keeps at most one actionable card per
     * worksite, so there is never an ambiguous Undo to tap.
     */
    private fun replacePrevious(worksite: WorkLocation, clockType: ClockType) {
        val previous = when (clockType) {
            ClockType.CLOCK_IN -> ClockType.CLOCK_OUT
            ClockType.CLOCK_OUT -> ClockType.CLOCK_IN
        }
        cancel(worksite, previous)
    }

    /** Dismisses the [worksite]/[clockType] card if it is still showing; a no-op if it is not. */
    override fun cancel(worksite: WorkLocation, clockType: ClockType) {
        val id = notificationId(worksite.id, clockType)
        Log.d(TAG, "cancel $clockType notification for ${worksite.id}")
        NotificationManagerCompat.from(appContext).cancel(id)
    }

    private fun baseBuilder(title: String): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
    }

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) {
            Log.d(TAG, "notifications disabled; skipping post")
            return
        }
        try {
            manager.notify(id, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on 13+; the event is still recorded by the strategy.
            Log.w(TAG, "notify blocked (missing POST_NOTIFICATIONS)", e)
        }
    }

    private fun pendingIntent(
        action: String,
        locationId: String,
        clockType: ClockType,
        notificationId: Int,
        epochMillis: Long? = null,
    ): PendingIntent {
        val intent = ClockActionReceiver.actionIntent(
            appContext, action, locationId, clockType, notificationId, epochMillis,
        )
        // Unique request code per (action, notification) so extras aren't collapsed across posts.
        val requestCode = (action + notificationId).hashCode()
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stable per-worksite, per-type id so a later state change replaces (not stacks) the card. */
    private fun notificationId(locationId: String, clockType: ClockType): Int =
        (locationId.hashCode() * 31 + clockType.ordinal) and 0x7FFFFFFF

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.clock_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.clock_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "ClockNotifier"
        const val CHANNEL_ID = "auto_clock"
    }
}
