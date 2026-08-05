package com.jaustinjr.employeeattendance.attendance

import android.util.Log
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference

/**
 * Strategy for what happens, and how visibly, when the user arrives at or leaves a worksite. Each
 * [ClockNotificationPreference] maps to one implementation, letting the auto-clock engine switch
 * behavior purely by the user's setting (see [forPreference]).
 *
 * Every implementation is guarded on the *attendance* state rather than firing blindly off physical
 * proximity: the proximity layer only knows INSIDE/OUTSIDE, so without this guard a departure after
 * an undone (or never-made) clock-in would still record — and announce — a clock-out for a session
 * that does not exist.
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
            ClockNotificationPreference.CONFIRM -> ConfirmClockStrategy(attendance, notifier)
        }
    }
}

/**
 * Shared guard for every strategy: a boundary crossing only means something when it actually changes
 * the worksite's clocked-in state. Arriving while already clocked in, and leaving while not clocked
 * in, are both no-ops — the latter is what stops an undone clock-in from being followed by a phantom
 * clock-out when the user later walks out of the radius.
 */
abstract class GuardedClockStrategy(
    protected val attendance: AttendanceRepository,
) : ClockNotificationStrategy {

    final override fun onArrived(worksite: WorkLocation) {
        if (isClockedIn(worksite)) {
            Log.d(TAG, "arrived at ${worksite.id} but already clocked in; skipping")
            return
        }
        onCrossing(worksite, ClockType.CLOCK_IN)
    }

    final override fun onDeparted(worksite: WorkLocation) {
        if (!isClockedIn(worksite)) {
            Log.d(TAG, "departed ${worksite.id} but not clocked in; skipping")
            return
        }
        onCrossing(worksite, ClockType.CLOCK_OUT)
    }

    /** Handles a boundary crossing that genuinely changes state; [clockType] is the implied event. */
    protected abstract fun onCrossing(worksite: WorkLocation, clockType: ClockType)

    /** Records [clockType] for [worksite] against the attendance log. */
    protected fun record(worksite: WorkLocation, clockType: ClockType) = when (clockType) {
        ClockType.CLOCK_IN -> attendance.recordClockIn(worksite.id)
        ClockType.CLOCK_OUT -> attendance.recordClockOut(worksite.id)
    }

    private fun isClockedIn(worksite: WorkLocation): Boolean =
        attendance.attendance.value[worksite.id]?.isClockedIn == true

    private companion object {
        private const val TAG = "ClockStrategy"
    }
}

/** Records immediately, no notification. Most seamless. */
class SilentClockStrategy(
    attendance: AttendanceRepository,
) : GuardedClockStrategy(attendance) {
    override fun onCrossing(worksite: WorkLocation, clockType: ClockType) {
        record(worksite, clockType)
    }
}

/** Records immediately and posts a notification with an Undo action. Hands-off but recoverable. */
class NotifyWithUndoStrategy(
    attendance: AttendanceRepository,
    private val notifier: ClockNotifications,
) : GuardedClockStrategy(attendance) {
    override fun onCrossing(worksite: WorkLocation, clockType: ClockType) {
        record(worksite, clockType)
        notifier.notifyRecorded(worksite, clockType, withUndo = true)
    }
}

/**
 * Records nothing on its own; posts a notification asking the user to confirm. The actual clock event
 * is recorded by [ClockActionReceiver] only if the user taps Confirm. Safest against false triggers.
 */
class ConfirmClockStrategy(
    attendance: AttendanceRepository,
    private val notifier: ClockNotifications,
) : GuardedClockStrategy(attendance) {
    override fun onCrossing(worksite: WorkLocation, clockType: ClockType) =
        notifier.notifyConfirm(worksite, clockType)
}
