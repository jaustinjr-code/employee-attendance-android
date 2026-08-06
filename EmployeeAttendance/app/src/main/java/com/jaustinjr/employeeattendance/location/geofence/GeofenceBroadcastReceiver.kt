package com.jaustinjr.employeeattendance.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives OS geofence transitions and forwards them into the app's
 * [com.jaustinjr.employeeattendance.location.proximity.ProximityRepository].
 *
 * The forwarding happens on a coroutine behind `goAsync()` rather than inline on the main thread,
 * so that it can wait for app startup to finish before emitting (a proximity event emitted with no
 * subscriber attached is dropped, losing an automatic clock in/out) and so that constructing the
 * encrypted proximity store does no disk or Keystore work on the main thread. See issue #19 and
 * [EmployeeAttendanceApplication].
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GEOFENCE_EVENT) return

        val event = GeofencingEvent.fromIntent(intent)
        if (event == null || event.hasError()) {
            Log.w(TAG, "Geofence event missing or errored: ${event?.errorCode}")
            return
        }

        val state = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER,
            Geofence.GEOFENCE_TRANSITION_DWELL -> ProximityState.INSIDE
            Geofence.GEOFENCE_TRANSITION_EXIT -> ProximityState.OUTSIDE
            else -> return
        }

        val triggering = event.triggeringGeofences.orEmpty()
        if (triggering.isEmpty()) return

        val app = context.applicationContext as EmployeeAttendanceApplication

        // Handed off to a coroutine rather than done inline, for two reasons (issue #19):
        //
        // 1. Ordering. Proximity events are a replay-0 SharedFlow, so a transition emitted before
        //    AttendanceAutoClockController has subscribed is silently dropped — a missed automatic
        //    clock in/out. Android can cold-start this process purely to deliver a geofence
        //    transition, so this receiver can genuinely run while Application.onCreate's background
        //    wiring is still in flight. awaitStarted() makes the consumer-before-producer ordering
        //    hold for this producer too.
        // 2. Threading. onReceive runs on the main thread, and touching container.proximityRepository
        //    constructs an EncryptedSharedPreferences-backed store (Keystore + disk I/O). Reading it
        //    off the main thread keeps that work off the UI thread here as well as at startup.
        //
        // goAsync() keeps the receiver (and the process) alive across the suspension. Startup is a
        // few prefs reads, so this settles in milliseconds — far inside the ~10s broadcast budget.
        val pendingResult = goAsync()
        // Dispatchers.IO, not the scope's default: reading container.proximityRepository below is
        // blocking Keystore + disk work and must not occupy a Dispatchers.Default worker.
        val job = app.applicationScope.launch(Dispatchers.IO) {
            try {
                app.awaitStarted()
                val repository = app.container.proximityRepository
                triggering.forEach { geofence ->
                    Log.d(TAG, "transition=$state for geofence=${geofence.requestId}")
                    repository.onGeofenceTransition(geofence.requestId, state)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never let a startup failure take down the process from a broadcast.
                Log.e(TAG, "Failed to forward geofence transition", e)
            }
        }
        // finish() is hung off job completion rather than a `finally` inside the body: if
        // applicationScope were already cancelled, the coroutine is born cancelled and its body —
        // including any `finally` — never runs, leaking the PendingResult until the OS times the
        // receiver out. invokeOnCompletion fires even for an already-completed job.
        job.invokeOnCompletion { pendingResult.finish() }
    }

    companion object {
        const val ACTION_GEOFENCE_EVENT =
            "com.jaustinjr.employeeattendance.action.GEOFENCE_EVENT"
        private const val TAG = "GeoRcvr"
    }
}
