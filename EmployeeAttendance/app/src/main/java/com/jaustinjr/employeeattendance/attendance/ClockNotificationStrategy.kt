package com.jaustinjr.employeeattendance.attendance

import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference

/**
 * Strategy for what happens, and how visibly, when the user arrives at or leaves a worksite. Each
 * [ClockNotificationPreference] maps to one implementation, letting the auto-clock engine switch
 * behavior purely by the user's setting (see [forPreference]).
 */
interface ClockNotificationStrategy {
    /** The user arrived at [worksite] (geofence ENTER). */
    fun onArrived(worksite: WorkLocation)

    /** The user left [worksite] (geofence EXIT). */
    fun onDeparted(worksite: WorkLocation)

    companion object {
        /** Selects the strategy for the current [preference]. */
        fun forPreference(
            preference: ClockNotificationPreference,
            attendance: AttendanceRepository,
            notifier: ClockNotifications,
        ): ClockNotificationStrategy = when (preference) {
            ClockNotificationPreference.SILENT -> SilentClockStrategy(attendance)
            ClockNotificationPreference.NOTIFY_UNDO -> NotifyWithUndoStrategy(attendance, notifier)
            ClockNotificationPreference.CONFIRM -> ConfirmClockStrategy(notifier)
        }
    }
}

/** Records immediately, no notification. Most seamless. */
class SilentClockStrategy(
    private val attendance: AttendanceRepository,
) : ClockNotificationStrategy {
    override fun onArrived(worksite: WorkLocation) = attendance.recordClockIn(worksite.id)
    override fun onDeparted(worksite: WorkLocation) = attendance.recordClockOut(worksite.id)
}

/** Records immediately and posts a notification with an Undo action. Hands-off but recoverable. */
class NotifyWithUndoStrategy(
    private val attendance: AttendanceRepository,
    private val notifier: ClockNotifications,
) : ClockNotificationStrategy {
    override fun onArrived(worksite: WorkLocation) {
        attendance.recordClockIn(worksite.id)
        notifier.notifyRecorded(worksite, ClockType.CLOCK_IN, withUndo = true)
    }

    override fun onDeparted(worksite: WorkLocation) {
        attendance.recordClockOut(worksite.id)
        notifier.notifyRecorded(worksite, ClockType.CLOCK_OUT, withUndo = true)
    }
}

/**
 * Records nothing on its own; posts a notification asking the user to confirm. The actual clock event
 * is recorded by [ClockActionReceiver] only if the user taps Confirm. Safest against false triggers.
 */
class ConfirmClockStrategy(
    private val notifier: ClockNotifications,
) : ClockNotificationStrategy {
    override fun onArrived(worksite: WorkLocation) =
        notifier.notifyConfirm(worksite, ClockType.CLOCK_IN)

    override fun onDeparted(worksite: WorkLocation) =
        notifier.notifyConfirm(worksite, ClockType.CLOCK_OUT)
}
