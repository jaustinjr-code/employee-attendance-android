package com.jaustinjr.employeeattendance.statusupdate

import android.util.Log
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Policy for every clock-out in the app: decides whether Status Update surfaces at all, and if so
 * whether as the in-app "Start status update?" pop-up ([pendingPrompt]) or as a notification
 * (posted through [StatusUpdateNotifications]).
 *
 * Takes [enabled] as a bare `StateFlow<Boolean>` rather than the whole
 * [StatusUpdateSettingsStore] — mirroring
 * [com.jaustinjr.employeeattendance.attendance.AttendanceAutoClockController] taking
 * `preference: StateFlow<ClockNotificationPreference>` rather than the whole
 * `ClockNotificationSettingsStore` — so this class needs no `Context` and is constructible in a
 * plain JVM test.
 *
 * This must be reachable with **no screen visible**: a geofence departure can cold-start the
 * process and drive an automatic clock-out straight into [onClockOut] before any Activity exists.
 * It is wired as an app-scoped singleton (see `AppContainer`) for exactly that reason — never
 * created per-screen.
 *
 * There is no resurfacing by design: dismissing the pop-up or the notification (or backing out of
 * the card stack) simply drops the pending state. Nothing here re-prompts, badges, or re-notifies.
 *
 * State mutations are guarded with `synchronized(this)` blocks rather than `@Synchronized` methods
 * wherever a notifier call follows: the notifier does one-way IPC into `system_server`, and this
 * app's convention is to never hold a monitor across that (see [onClockOut]/[onClockOutUndone]).
 */
class StatusUpdateCoordinator(
    private val enabled: StateFlow<Boolean>,
    private val foregroundTracker: AppForegroundTracker,
    private val notifier: StatusUpdateNotifications,
    private val repository: StatusUpdateRepository,
    private val attendanceRepository: AttendanceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worksiteName: (locationId: String) -> String? = { null },
) {

    private val _pendingPrompt = MutableStateFlow<StatusUpdateRequest?>(null)

    /** Non-null while the in-app "Start status update?" pop-up should show. */
    val pendingPrompt: StateFlow<StatusUpdateRequest?> = _pendingPrompt.asStateFlow()

    private val _undoneClockOuts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits a [StatusUpdateRequest.clockOutId] whenever [onClockOutUndone] reverses it, so a card
     * stack already open for that clock-out (if any) can close itself. */
    val undoneClockOuts: SharedFlow<String> = _undoneClockOuts.asSharedFlow()

    /**
     * Called for every clock-out — manual, automatic, or confirmed from the clock-out
     * notification — regardless of source. No-ops entirely when the setting is disabled.
     *
     * [trigger] decides the surface:
     * - [StatusUpdateTrigger.MANUAL]: always the in-app pop-up (a manual tap is by definition in
     *   the foreground).
     * - [StatusUpdateTrigger.AUTO]: the pop-up if the app is currently foregrounded, otherwise a
     *   notification.
     * - [StatusUpdateTrigger.NOTIFICATION_CONFIRMED]: always a notification — the user was just
     *   interacting with the notification shade, not the app, even if the app happens to be
     *   foregrounded at that instant.
     */
    fun onClockOut(locationId: String, clockOutAtMillis: Long, trigger: StatusUpdateTrigger) {
        val request = StatusUpdateRequest(locationId, clockOutAtMillis)
        var showPrompt = false
        synchronized(this) {
            if (!enabled.value) {
                Log.d(TAG, "onClockOut: disabled; ignoring $trigger clock-out for $locationId")
                return
            }
            showPrompt = when (trigger) {
                StatusUpdateTrigger.MANUAL -> true
                StatusUpdateTrigger.AUTO -> foregroundTracker.isForeground.value
                StatusUpdateTrigger.NOTIFICATION_CONFIRMED -> false
            }
            if (showPrompt) _pendingPrompt.value = request
        }
        Log.d(
            TAG,
            "onClockOut: $trigger clock-out for $locationId -> " +
                if (showPrompt) "prompt" else "notification",
        )
        if (!showPrompt) notifier.notifyPending(request)
    }

    /** "Begin" tapped: clears the prompt and returns the request to open, or null if none. */
    @Synchronized
    fun acceptPrompt(): StatusUpdateRequest? {
        val request = _pendingPrompt.value ?: return null
        _pendingPrompt.value = null
        return request
    }

    /** "Not now" / dialog dismissed: clears the prompt; that status update is skipped. */
    @Synchronized
    fun dismissPrompt() {
        _pendingPrompt.value = null
    }

    /**
     * Validates a [request] that arrived via the "status update waiting" notification's tap (cold
     * or warm start). `MainActivity` is exported, so its launch extras are untrusted input:
     * returns [request] back only if the attendance log actually holds that clock-out, and it has
     * not already been completed — otherwise null, and the caller must not open the deck.
     */
    @Synchronized
    fun claimNotificationRequest(request: StatusUpdateRequest): StatusUpdateRequest? {
        val hasEvent = attendanceRepository.hasClockOutEvent(request.locationId, request.clockOutAtMillis)
        if (!hasEvent) {
            Log.w(TAG, "claimNotificationRequest: no such clock-out; ignoring $request")
            return null
        }
        val alreadyCompleted = repository.statusUpdates.value.any { it.clockOutId == request.clockOutId }
        if (alreadyCompleted) {
            Log.d(TAG, "claimNotificationRequest: already completed; ignoring $request")
            return null
        }
        return request
    }

    /**
     * Reverses [onClockOut] for the clock-out at [locationId]/[clockOutAtMillis] (see
     * `ClockActionHandler.undo`): retracts a live pending prompt or notification for it, and tells
     * an already-open card stack for it to close via [undoneClockOuts].
     */
    fun onClockOutUndone(locationId: String, clockOutAtMillis: Long) {
        val request = StatusUpdateRequest(locationId, clockOutAtMillis)
        synchronized(this) {
            if (_pendingPrompt.value == request) {
                _pendingPrompt.value = null
            }
        }
        Log.d(TAG, "onClockOutUndone: $request")
        notifier.cancel(request)
        _undoneClockOuts.tryEmit(request.clockOutId)
    }

    /**
     * Saves the completed update via the repository (`completedAt` from the injected [clock]),
     * together with the shift it closes: the clock-in before it and the worksite's current name.
     * An update with every answer blank is not kept, since there is nothing to look back on.
     */
    fun complete(
        request: StatusUpdateRequest,
        didToday: String,
        plannedTomorrow: String,
        couldNotDo: String,
    ) {
        val update = StatusUpdate(
            clockOutId = request.clockOutId,
            didToday = didToday,
            plannedTomorrow = plannedTomorrow,
            couldNotDo = couldNotDo,
            completedAtMillis = clock(),
            clockOutAtMillis = request.clockOutAtMillis,
            clockInAtMillis = attendanceRepository.clockInBefore(
                request.locationId,
                request.clockOutAtMillis,
            ),
            worksiteName = worksiteName(request.locationId),
        )
        if (!update.hasAnyAnswer) {
            Log.d(TAG, "complete: every answer blank; not saving ${request.clockOutId}")
            return
        }
        repository.save(update)
    }

    private companion object {
        private const val TAG = "StatusUpdateCoord"
    }
}
