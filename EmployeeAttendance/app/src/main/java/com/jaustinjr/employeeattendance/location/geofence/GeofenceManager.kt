package com.jaustinjr.employeeattendance.location.geofence

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.jaustinjr.employeeattendance.location.proximity.GeofenceTarget
import kotlinx.coroutines.tasks.await

/**
 * Registers and clears OS-managed geofences via Play Services. Geofences are the power-efficient
 * path for background proximity: the system watches the regions using hardware/coarse signals and
 * only wakes the app on an actual enter/exit, so we don't have to poll location ourselves.
 *
 * Requires background location permission to trigger while the app is not in the foreground; the
 * caller is responsible for holding it (hence [SuppressLint] "MissingPermission").
 */
class GeofenceManager(
    context: Context,
) {

    private val appContext = context.applicationContext
    private val client: GeofencingClient = LocationServices.getGeofencingClient(appContext)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(appContext, GeofenceBroadcastReceiver::class.java)
            .setAction(GeofenceBroadcastReceiver.ACTION_GEOFENCE_EVENT)
        // Geofence transitions must be delivered as a mutable PendingIntent so the system can
        // populate the triggering event; FLAG_MUTABLE is required on Android 12+.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(appContext, GEOFENCE_REQUEST_CODE, intent, flags)
    }

    /**
     * Replaces the currently registered geofences with [targets]. Existing geofences are removed
     * first so this is idempotent and safe to call whenever the registered locations change.
     */
    @SuppressLint("MissingPermission")
    suspend fun register(targets: List<GeofenceTarget>) {
        clear()
        if (targets.isEmpty()) return

        val request = GeofencingRequest.Builder()
            // Fire ENTER immediately if the user is already inside when the geofence is added.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(targets.map { it.toGeofence() })
            .build()

        client.addGeofences(request, pendingIntent).await()
    }

    /** Removes all geofences registered through this manager. */
    suspend fun clear() {
        client.removeGeofences(pendingIntent).await()
    }

    private fun GeofenceTarget.toGeofence(): Geofence = Geofence.Builder()
        .setRequestId(id)
        .setCircularRegion(latitudeDegrees, longitudeDegrees, radiusMeters)
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .build()

    companion object {
        private const val GEOFENCE_REQUEST_CODE = 1001
    }
}
