package com.jaustinjr.employeeattendance.statusupdate

/**
 * What triggered a clock-out, for the purposes of deciding how the Status Update feature should
 * surface: an in-app pop-up, a notification, or nothing at all.
 *
 * - [MANUAL]: the user tapped the clock-out button. Always in the foreground by definition, but
 *   still routed through [StatusUpdateCoordinator]'s policy rather than assumed.
 * - [AUTO]: a geofence departure drove the clock-out (see
 *   [com.jaustinjr.employeeattendance.attendance.AttendanceAutoClockController]). Whether that
 *   shows a pop-up or a notification depends on whether the app is currently in the foreground.
 * - [NOTIFICATION_CONFIRMED]: the user tapped "Confirm" on the existing clock-out confirmation
 *   notification (see `ConfirmClockStrategy`). Always surfaces as a notification, even if the app
 *   happens to be in the foreground at that moment, since the user was already interacting with
 *   the notification shade rather than the app.
 */
enum class StatusUpdateTrigger { MANUAL, AUTO, NOTIFICATION_CONFIRMED }

/**
 * A pending status update for one clock-out. [clockOutId] is derived rather than stored, so the
 * two halves that actually identify the clock-out ([locationId], [clockOutAtMillis]) can never
 * drift out of sync with the id built from them.
 */
data class StatusUpdateRequest(val locationId: String, val clockOutAtMillis: Long) {
    val clockOutId: String get() = "$locationId@$clockOutAtMillis"
}

/**
 * A completed Status Update: the answers captured (keyed by [StatusUpdateQuestion]; a missing key means unanswered) for one clock-out (a work shift), plus a
 * snapshot of the shift it belongs to so history still reads correctly after the worksite is renamed
 * or removed, or the attendance log is changed.
 *
 * @param clockInAtMillis the clock-in that opened the shift, or null if none was on record.
 * @param worksiteName the worksite's name when the update was saved; null for the general timeclock
 *   or an unknown worksite.
 * @param editedAtMillis when the answers were last edited from history, or null if never edited.
 */
data class StatusUpdate(
    val clockOutId: String,
    val answers: Map<StatusUpdateQuestion, String>,
    val completedAtMillis: Long,
    val clockOutAtMillis: Long = completedAtMillis,
    val clockInAtMillis: Long? = null,
    val worksiteName: String? = null,
    val editedAtMillis: Long? = null,
) {
    /** Whether at least one answer has content; only these are kept in history. */
    val hasAnyAnswer: Boolean get() = answers.values.any { it.isNotBlank() }
}
