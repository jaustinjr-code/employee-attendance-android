package com.jaustinjr.employeeattendance.location.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.location.proximity.ProximityState

/**
 * Receives OS geofence transitions and forwards them into the app's
 * [com.jaustinjr.employeeattendance.location.proximity.ProximityRepository]. The work done here is
 * intentionally minimal (an in-memory state update) so it completes well within the broadcast
 * time budget.
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

        val repository = (context.applicationContext as EmployeeAttendanceApplication)
            .container.proximityRepository

        event.triggeringGeofences?.forEach { geofence ->
            Log.d(TAG, "transition=$state for geofence=${geofence.requestId}")
            repository.onGeofenceTransition(geofence.requestId, state)
        }
    }

    companion object {
        const val ACTION_GEOFENCE_EVENT =
            "com.jaustinjr.employeeattendance.action.GEOFENCE_EVENT"
        private const val TAG = "GeoRcvr"
    }
}
