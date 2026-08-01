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
 */
class ClockActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val locationId = intent.getStringExtra(EXTRA_LOCATION_ID) ?: return
        val clockType = intent.getStringExtra(EXTRA_CLOCK_TYPE)
            ?.let { runCatching { ClockType.valueOf(it) }.getOrNull() } ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        val repository = (context.applicationContext as EmployeeAttendanceApplication)
            .container.attendanceRepository

        when (action) {
            ACTION_UNDO -> {
                Log.d(TAG, "undo $clockType for $locationId")
                repository.undoLast(locationId)
            }
            ACTION_CONFIRM -> {
                Log.d(TAG, "confirm $clockType for $locationId")
                when (clockType) {
                    ClockType.CLOCK_IN -> repository.recordClockIn(locationId)
                    ClockType.CLOCK_OUT -> repository.recordClockOut(locationId)
                }
            }
            else -> return
        }

        if (notificationId >= 0) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }

    companion object {
        private const val TAG = "ClockAction"

        const val ACTION_UNDO = "com.jaustinjr.employeeattendance.action.CLOCK_UNDO"
        const val ACTION_CONFIRM = "com.jaustinjr.employeeattendance.action.CLOCK_CONFIRM"

        private const val EXTRA_ACTION = "extra_action"
        private const val EXTRA_LOCATION_ID = "extra_location_id"
        private const val EXTRA_CLOCK_TYPE = "extra_clock_type"
        private const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        /** Builds the [Intent] backing a notification action button. */
        fun actionIntent(
            context: Context,
            action: String,
            locationId: String,
            clockType: ClockType,
            notificationId: Int,
        ): Intent = Intent(context, ClockActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ACTION, action)
            putExtra(EXTRA_LOCATION_ID, locationId)
            putExtra(EXTRA_CLOCK_TYPE, clockType.name)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
    }
}
