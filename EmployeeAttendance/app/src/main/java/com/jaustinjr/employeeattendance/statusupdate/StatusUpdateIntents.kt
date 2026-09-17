package com.jaustinjr.employeeattendance.statusupdate

import android.content.Intent

/**
 * The launch-intent contract for opening the Status Update card stack — from the "waiting"
 * notification's tap, or a cold/warm start that was already carrying a pending request.
 * `MainActivity` reads this via [consumeRequest] from its launch intent (and `onNewIntent`).
 *
 * `MainActivity` is exported, so a request read from these extras is untrusted input — callers
 * must still validate it against the real attendance log via
 * [StatusUpdateCoordinator.claimNotificationRequest] before acting on it.
 */
object StatusUpdateIntents {
    const val EXTRA_LOCATION_ID = "com.jaustinjr.employeeattendance.statusupdate.LOCATION_ID"
    const val EXTRA_CLOCK_OUT_AT = "com.jaustinjr.employeeattendance.statusupdate.CLOCK_OUT_AT"

    /**
     * Extracts and *consumes* the pending [StatusUpdateRequest] from [intent]'s extras: both
     * extras are removed after a successful read, so a later replay of the same `Intent` instance
     * (a configuration change re-delivering it, or `onNewIntent` being called again for reasons
     * unrelated to a fresh notification tap) does not re-open the deck.
     *
     * Returns null when [Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY] is set — a relaunch from the
     * recent-tasks list carries this intent's extras without an actual new notification tap having
     * happened — or when the extras are simply absent.
     */
    fun consumeRequest(intent: Intent?): StatusUpdateRequest? {
        if (intent == null) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val request = requestFrom(intent) ?: return null
        intent.removeExtra(EXTRA_LOCATION_ID)
        intent.removeExtra(EXTRA_CLOCK_OUT_AT)
        return request
    }

    /** Builds the extras bundle a status-update-waiting notification's tap should carry. */
    fun putExtras(intent: Intent, request: StatusUpdateRequest): Intent = intent.apply {
        putExtra(EXTRA_LOCATION_ID, request.locationId)
        putExtra(EXTRA_CLOCK_OUT_AT, request.clockOutAtMillis)
    }

    private fun requestFrom(intent: Intent): StatusUpdateRequest? {
        val locationId = intent.getStringExtra(EXTRA_LOCATION_ID) ?: return null
        if (!intent.hasExtra(EXTRA_CLOCK_OUT_AT)) return null
        val clockOutAt = intent.getLongExtra(EXTRA_CLOCK_OUT_AT, 0L)
        return StatusUpdateRequest(locationId, clockOutAt)
    }
}
