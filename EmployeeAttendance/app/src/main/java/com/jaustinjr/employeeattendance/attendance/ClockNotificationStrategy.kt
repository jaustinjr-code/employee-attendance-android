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
        if (attendance.isClockedIn(worksite.id)) {
            Log.d(TAG, "arrived at ${worksite.id} but already clocked in; skipping")
            onSkipped(worksite, ClockType.CLOCK_IN)
            return
        }
        onCrossing(worksite, ClockType.CLOCK_IN)
    }

    final override fun onDeparted(worksite: WorkLocation) {
        if (!attendance.isClockedIn(worksite.id)) {
            Log.d(TAG, "departed ${worksite.id} but not clocked in; skipping")
            onSkipped(worksite, ClockType.CLOCK_OUT)
            return
        }
        onCrossing(worksite, ClockType.CLOCK_OUT)
    }

    /** Handles a boundary crossing that genuinely changes state; [clockType] is the implied event. */
    protected abstract fun onCrossing(worksite: WorkLocation, clockType: ClockType)

    /**
     * Called instead of [onCrossing] when the crossing changes nothing. [clockType] is the event
     * that was *not* produced. Strategies that leave something pending on the previous crossing use
     * this to retract it — see [ConfirmClockStrategy].
     */
    protected open fun onSkipped(worksite: WorkLocation, clockType: ClockType) = Unit

    /**
     * Records [clockType] for [worksite], re-checking the clocked-in state atomically with the
     * append. The check above already filtered the common case; this one closes the window in which
     * another producer (manual button, notification action) records the same transition first.
     *
     * @return true if the event was recorded — false means it was redundant and nothing changed, so
     *   callers must not announce it.
     */
    protected fun record(worksite: WorkLocation, clockType: ClockType): Boolean =
        attendance.recordIfStateChanges(worksite.id, clockType)

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
        if (record(worksite, clockType)) {
            notifier.notifyRecorded(worksite, clockType, withUndo = true)
        }
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

    /**
     * A confirm prompt outlives the crossing that posted it, so a skipped crossing means the *other*
     * prompt is now unanswerable and must be retracted. Concretely: the user arrives, ignores
     * "Arrived — clock in?", and leaves again. The departure records nothing (there is no session),
     * but the clock-in card is still sitting in the shade — tapping it would open a session at a
     * worksite the user has already left, and no later geofence exit would ever close it.
     */
    override fun onSkipped(worksite: WorkLocation, clockType: ClockType) =
        notifier.cancel(worksite, opposite(clockType))

    private fun opposite(clockType: ClockType): ClockType = when (clockType) {
        ClockType.CLOCK_IN -> ClockType.CLOCK_OUT
        ClockType.CLOCK_OUT -> ClockType.CLOCK_IN
    }
}
