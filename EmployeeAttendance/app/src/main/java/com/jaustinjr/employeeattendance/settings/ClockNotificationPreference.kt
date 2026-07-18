package com.jaustinjr.employeeattendance.settings

/**
 * How visible an automatic clock-in/out should be to the user. The user picks one in Settings; the
 * auto-clock engine maps the choice to a [com.jaustinjr.employeeattendance.attendance.ClockNotificationStrategy].
 */
enum class ClockNotificationPreference {
    /** Record silently; no notification. Most seamless, least recoverable if a geofence misfires. */
    SILENT,

    /** Record immediately, but post a notification with an Undo action. The recommended default. */
    NOTIFY_UNDO,

    /** Do not record automatically; post a notification asking the user to confirm first. */
    CONFIRM,
    ;

    companion object {
        /** Balances hands-off operation with recoverability from false triggers. */
        val DEFAULT = NOTIFY_UNDO
    }
}
