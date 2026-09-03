package com.jaustinjr.employeeattendance.attendance

import android.util.Log
import com.jaustinjr.employeeattendance.location.proximity.ProximityEvent
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/**
 * The hands-off clock engine. It is the (previously missing) consumer of the proximity layer's
 * [ProximityEvent]s: when the user crosses a worksite boundary the geofence/proximity pipeline emits
 * `Arrived`/`Departed`, and this controller turns that into an attendance record — its visibility
 * governed by the user's current [ClockNotificationSettingsStore] preference via a
 * [ClockNotificationStrategy].
 *
 * It holds no per-event state; the strategy is chosen fresh from the current [preference] each time,
 * so changing the setting takes effect on the very next arrival/departure with no restart.
 */
class AttendanceAutoClockController(
    private val proximityEvents: SharedFlow<ProximityEvent>,
    private val workLocationRepository: WorkLocationRepository,
    private val attendanceRepository: AttendanceRepository,
    private val notifier: ClockNotifications,
    private val preference: StateFlow<ClockNotificationPreference>,
) {

    /**
     * Completes once this controller's collector is actually subscribed to [proximityEvents].
     *
     * [proximityEvents] is a replay-0 [SharedFlow], so anything emitted before the collector is
     * attached is dropped — and a dropped Arrived/Departed is a missed automatic clock in/out.
     * [start] returns before the collector is live (it launches a coroutine), so callers that are
     * about to enable an event producer must wait on [awaitSubscribed] first. See issue #19.
     */
    private val subscribed = CompletableDeferred<Unit>()

    /** Begins consuming proximity events on [scope]; call once with an app-lifetime scope. */
    fun start(scope: CoroutineScope): Job {
        Log.d(TAG, "start: consuming proximity events for auto clock in/out")
        val job = scope.launch {
            proximityEvents
                .onSubscription {
                    Log.d(TAG, "subscribed to proximity events")
                    subscribed.complete(Unit)
                }
                .collect { event ->
                    // One bad event must not end the stream. handle() records attendance and posts
                    // notifications, either of which can throw on a device-specific problem (a
                    // missing notification channel, a storage error). An exception escaping the
                    // collector cancels the job, and because the scope is a SupervisorJob nothing
                    // restarts it — auto clock in/out would be inert for the rest of the process
                    // after a single failure, while startup had already reported success.
                    try {
                        handle(event)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to handle $event; continuing to consume", e)
                    }
                }
        }
        // Failure-safety for [subscribed]. It is completed from inside the collector, so if that
        // coroutine never reaches onSubscription — [scope] already cancelled (the coroutine is then
        // born cancelled and its body never runs), or a throw during setup — nothing would ever
        // complete it. On a SupervisorJob scope that failure is not propagated anywhere, so every
        // awaitSubscribed() caller would hang forever, and with them app startup and any geofence
        // broadcast waiting on it. invokeOnCompletion runs even for an already-completed job, so
        // waiters are always released; auto clock-in is then degraded rather than the app wedged.
        job.invokeOnCompletion { cause ->
            if (subscribed.complete(Unit)) {
                Log.w(TAG, "collector finished before subscribing; released waiters", cause)
            } else if (cause != null && cause !is CancellationException) {
                // The collector died *after* subscribing, so waiters were already released and
                // startup reported success. Nothing restarts it on a SupervisorJob scope, so every
                // later Arrived/Departed is silently dropped and hands-off clocking is inert for
                // the rest of the process. Without this branch that happens with no log at all.
                Log.e(TAG, "proximity collector stopped; auto clock in/out is now inert", cause)
            }
        }
        return job
    }

    /** Suspends until [start]'s collector is subscribed. See [subscribed]. */
    suspend fun awaitSubscribed() {
        subscribed.await()
    }

    private fun handle(event: ProximityEvent) {
        val worksite = resolve(event.targetId)
        if (worksite == null) {
            Log.w(TAG, "no registered worksite for target ${event.targetId}; ignoring $event")
            return
        }
        val current = preference.value
        val strategy = ClockNotificationStrategy.forPreference(
            preference = current,
            attendance = attendanceRepository,
            notifier = notifier,
        )
        Log.d(TAG, "handle $event via $current")
        when (event) {
            is ProximityEvent.Arrived -> strategy.onArrived(worksite)
            is ProximityEvent.Departed -> strategy.onDeparted(worksite)
        }
    }

    private fun resolve(targetId: String): WorkLocation? =
        workLocationRepository.workLocations.value.firstOrNull { it.id == targetId }
            ?: workLocationRepository.activeWorkLocation.value?.takeIf { it.id == targetId }

    private companion object {
        private const val TAG = "AutoClock"
    }
}
