package com.jaustinjr.employeeattendance.devtools.facade

import android.util.Log
import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.attendance.ClockNotifications
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.location.registration.WorkLocation

/**
 * Posts the auto-clock notifications for **layout inspection only**, with their action buttons
 * defused.
 *
 * ### The problem this fixes
 * Posting a card is itself harmless — the developer tools record nothing when they post one. Its
 * buttons are not: [com.jaustinjr.employeeattendance.attendance.ClockNotifier] wires them to
 * [com.jaustinjr.employeeattendance.attendance.ClockActionReceiver], which resolves the ids the
 * notification carries against the real attendance repository.
 *
 * **Undo** is already well defended here: `undoEvent(locationId, type, epochMillis)` reverses only a
 * location's *latest* event, and only when the type and timestamp both match the ones the card
 * names. A preview names a synthetic event that was never recorded, so the undo finds nothing —
 * which is asserted by test rather than assumed.
 *
 * **Confirm** is the one that still bites without this class: it calls `recordClockIn` /
 * `recordClockOut` for the worksite id on the card, so confirming a prompt posted purely to look at
 * would write a genuine attendance event for a real worksite.
 *
 * ### The sandbox
 * The preview posts against `worksite.copy(id = ` [DEV_PREVIEW_WORKSITE_ID] `)`. The worksite **name**
 * is what the notification renders, so this is still the genuine card; only the id travelling in the
 * action `PendingIntent` changes. A `Confirm` therefore writes into a bucket no screen reads, and
 * the undo path is defended twice over — by the sandbox id and by the named-event check.
 *
 * `ClockNotifier`'s notification id derives from `locationId.hashCode()`, so a preview also gets its
 * own notification id and cannot replace (or be replaced by) a real card for the same worksite.
 *
 * The sandbox lives entirely on this side of the seam: no production type learns that previews
 * exist, and [ClockNotifications] gains no `preview` flag.
 */
interface DevNotificationPreview {

    /** Previews the "Clocked in/out" card, optionally with its Undo action. */
    fun previewRecordedNotification(worksite: WorkLocation, clockType: ClockType, withUndo: Boolean)

    /** Previews the "arrived/left — confirm?" prompt with its Confirm action. */
    fun previewConfirmNotification(worksite: WorkLocation, clockType: ClockType)

    companion object {
        /**
         * The throwaway worksite id every preview's action buttons resolve against. Never registered
         * as a worksite, so nothing reads attendance recorded under it.
         */
        const val DEV_PREVIEW_WORKSITE_ID = "dev-notification-preview"
    }
}

/** [DevNotificationPreview] over the real [ClockNotifications], with the id swap applied. */
class SandboxedDevNotificationPreview(
    private val notifications: ClockNotifications,
    private val clock: () -> Long = System::currentTimeMillis,
) : DevNotificationPreview {

    override fun previewRecordedNotification(
        worksite: WorkLocation,
        clockType: ClockType,
        withUndo: Boolean,
    ) {
        Log.d(TAG, "preview recorded $clockType for ${worksite.name}")
        // Synthetic: nothing was recorded, so there is no real event for the card to name. Built
        // against the sandbox id so the Undo action cannot name a genuine event either.
        val sandboxed = worksite.sandboxed()
        val event = AttendanceEvent(sandboxed.id, clockType, clock(), ClockSource.SIMULATED)
        notifications.notifyRecorded(sandboxed, event, withUndo)
    }

    override fun previewConfirmNotification(worksite: WorkLocation, clockType: ClockType) {
        Log.d(TAG, "preview confirm $clockType for ${worksite.name}")
        notifications.notifyConfirm(worksite.sandboxed(), clockType)
    }

    /** Keeps the name (which is what renders) and replaces the id (which is what acts). */
    private fun WorkLocation.sandboxed(): WorkLocation =
        copy(id = DevNotificationPreview.DEV_PREVIEW_WORKSITE_ID)

    private companion object {
        const val TAG = "DevNotifyPreview"
    }
}
