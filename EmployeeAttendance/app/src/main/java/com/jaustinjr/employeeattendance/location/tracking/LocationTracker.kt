package com.jaustinjr.employeeattendance.location.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.tasks.await

/**
 * UI- and lifecycle-agnostic source of location fixes. Callers are responsible for ensuring the
 * appropriate location permission is held before collecting; implementations assume it.
 */
interface LocationTracker {

    /**
     * A cold [Flow] of location fixes for the given [config]. Updates start when collection begins
     * and stop (removing the underlying platform request) when collection is cancelled, so the
     * flow's lifecycle directly controls battery use.
     */
    fun locationUpdates(config: LocationRequestConfig): Flow<LocationSample>

    /**
     * Fetches a single fresh fix, or null if one could not be obtained. Cheaper than opening an
     * update stream when only a one-shot reading is needed (e.g. an initial map position).
     */
    suspend fun currentLocation(priority: LocationPriority): LocationSample?
}

/**
 * [LocationTracker] backed by Google Play Services' Fused Location Provider, which fuses GPS, Wi-Fi
 * and cell signals and coordinates requests across apps for efficiency.
 *
 * Permission is the caller's responsibility (see [com.jaustinjr.employeeattendance.location.permission]),
 * so the platform calls are annotated [SuppressLint] with "MissingPermission".
 */
class FusedLocationTracker(
    context: Context,
) : LocationTracker {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    override fun locationUpdates(config: LocationRequestConfig): Flow<LocationSample> = callbackFlow {
        val request = LocationRequest.Builder(config.priority.toGmsPriority(), config.intervalMillis)
            .setMinUpdateIntervalMillis(config.minUpdateIntervalMillis)
            .setMaxUpdateDelayMillis(config.maxUpdateDelayMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toSample()) }
            }
        }

        // Deliver callbacks on the main looper; the callback body only forwards to the channel.
        client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())

        awaitClose { client.removeLocationUpdates(callback) }
    }
        // Conflate so a slow consumer always processes the freshest fix and never works through a
        // backlog of stale positions — keeps proximity decisions low-latency.
        .conflate()

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(priority: LocationPriority): LocationSample? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority.toGmsPriority())
            .build()
        return client.getCurrentLocation(request, null).await()?.toSample()
    }
}

private fun Location.toSample(): LocationSample = LocationSample(
    latitudeDegrees = latitude,
    longitudeDegrees = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    timestampEpochMillis = time,
)
