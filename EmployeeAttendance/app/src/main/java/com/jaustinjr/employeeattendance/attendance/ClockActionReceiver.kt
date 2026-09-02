package com.jaustinjr.employeeattendance.attendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication

/**
 * Handles the action buttons on auto clock-in/out notifications: **Undo** (reverse a just-recorded
 * event) and **Confirm** (record an event the user had to approve first). Kept minimal so it
 * completes within the broadcast time budget — it mutates the (in-memory + SharedPrefs) attendance
 * repository and dismisses the originating notification.
 *
 * The decision logic lives in [ClockActionHandler] so it can be unit-tested without a [Context];
 * this class only parses intent extras and does the Android-side dismissal.
 */
class ClockActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val locationId = intent.getStringExtra(EXTRA_LOCATION_ID) ?: return
        val clockType = intent.getStringExtra(EXTRA_CLOCK_TYPE)
            ?.let { runCatching { ClockType.valueOf(it) }.getOrNull() } ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        // Absent on Confirm prompts (nothing has been recorded yet) and on any pending intent
        // created before this extra existed; NO_EVENT makes an Undo without it a no-op rather than
        // a guess at which event was meant.
        val epochMillis = intent.getLongExtra(EXTRA_EPOCH_MILLIS, ClockActionHandler.NO_EVENT)

        val repository = (context.applicationContext as EmployeeAttendanceApplication)
            .container.attendanceRepository

        ClockActionHandler.handle(
            repository = repository,
            action = action,
            locationId = locationId,
            clockType = clockType,
            epochMillis = epochMillis,
        )

        // Dismissed either way: the user pressed the button, so the card should go. Whether the log
        // changed is the handler's business — a stale action is a no-op, not a different edit.
        if (notificationId >= 0) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }

    companion object {
        const val ACTION_UNDO = "com.jaustinjr.employeeattendance.action.CLOCK_UNDO"
        const val ACTION_CONFIRM = "com.jaustinjr.employeeattendance.action.CLOCK_CONFIRM"

        private const val EXTRA_ACTION = "extra_action"
        private const val EXTRA_LOCATION_ID = "extra_location_id"
        private const val EXTRA_CLOCK_TYPE = "extra_clock_type"
        private const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        private const val EXTRA_EPOCH_MILLIS = "extra_epoch_millis"

        /**
         * Builds the [Intent] backing a notification action button. [epochMillis] identifies the
         * already-recorded event an Undo button reverses; it is null for Confirm prompts, which have
         * nothing recorded yet.
         */
        fun actionIntent(
            context: Context,
            action: String,
            locationId: String,
            clockType: ClockType,
            notificationId: Int,
            epochMillis: Long? = null,
        ): Intent = Intent(context, ClockActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ACTION, action)
            putExtra(EXTRA_LOCATION_ID, locationId)
            putExtra(EXTRA_CLOCK_TYPE, clockType.name)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            if (epochMillis != null) putExtra(EXTRA_EPOCH_MILLIS, epochMillis)
        }
    }
}

/**
 * The framework-free half of [ClockActionReceiver]: what a notification action button actually does
 * to the attendance log.
 *
 * Both actions are scoped rather than "apply to whatever is latest", because a notification can
 * outlive what it announced. A clock-in card the user never swiped away is still tappable after the
 * clock-out has been recorded, and the old type-agnostic `undoLast(locationId)` would then have
 * reversed the *clock-out* — the opposite of what that button says it does.
 */
object ClockActionHandler {

    /** [epochMillis] value meaning "this intent names no specific event". */
    const val NO_EVENT = -1L

    private const val TAG = "ClockAction"

    /**
     * Applies [action] to [repository].
     *
     * @return true if the attendance log actually changed; false for an unknown action or a stale
     *   one whose event is already gone (or already recorded).
     */
    fun handle(
        repository: AttendanceRepository,
        action: String,
        locationId: String,
        clockType: ClockType,
        epochMillis: Long,
    ): Boolean = when (action) {
        ClockActionReceiver.ACTION_UNDO -> undo(repository, locationId, clockType, epochMillis)
        ClockActionReceiver.ACTION_CONFIRM -> confirm(repository, locationId, clockType)
        else -> false
    }

    private fun undo(
        repository: AttendanceRepository,
        locationId: String,
        clockType: ClockType,
        epochMillis: Long,
    ): Boolean {
        if (epochMillis == NO_EVENT) {
            Log.d(TAG, "undo $clockType for $locationId names no event; ignoring")
            return false
        }
        val undone = repository.undoEvent(locationId, clockType, epochMillis)
        if (!undone) {
            Log.d(TAG, "undo $clockType for $locationId at $epochMillis: already gone; ignoring")
        }
        return undone
    }

    private fun confirm(
        repository: AttendanceRepository,
        locationId: String,
        clockType: ClockType,
    ): Boolean {
        // Guarded like the auto path: the prompt may be stale (already confirmed from another card,
        // or clocked out manually since), and confirming it must not write a clock-out with no open
        // session behind it.
        if (repository.recordIfStateChanges(locationId, clockType) == null) {
            Log.d(TAG, "confirm $clockType ignored; $locationId already in the target state")
            return false
        }
        Log.d(TAG, "confirm $clockType for $locationId")
        return true
    }
}
