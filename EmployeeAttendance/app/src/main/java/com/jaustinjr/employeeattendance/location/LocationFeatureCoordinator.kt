package com.jaustinjr.employeeattendance.location

import android.util.Log
import com.jaustinjr.employeeattendance.location.geofence.GeofenceManager
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationTrackingController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * App-scoped glue that connects the moving parts of the location feature so the UI layer doesn't
 * have to: it reconciles background tracking + geofence registration with the current permission
 * and active work location, and feeds foreground fixes into proximity detection.
 *
 * Two reactive pipelines, started once from the Application:
 *  1. (permission x active work location) -> start/stop the tracking service and (re)register or
 *     clear geofences. Geofences are only registered when background tracking is permitted.
 *  2. (latest fix x active work location) -> compute foreground proximity. This covers the
 *     While-In-Use case (no geofences) and is harmless alongside geofences, since the proximity
 *     repository dedupes state.
 */
class LocationFeatureCoordinator(
    private val permissionRepository: LocationPermissionRepository,
    private val workLocationRepository: WorkLocationRepository,
    private val trackingController: LocationTrackingController,
    private val geofenceManager: GeofenceManager,
    private val locationState: LocationStateRepository,
    private val proximityRepository: ProximityRepository,
) {

    /** Starts the coordination pipelines on [scope]; call once with an app-lifetime scope. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                permissionRepository.permissionState,
                workLocationRepository.activeWorkLocation,
            ) { permission, activeLocation -> permission to activeLocation }
                .distinctUntilChanged()
                // collectLatest cancels an in-flight reconcile if inputs change again, so geofence
                // clear()/register() calls can't interleave across reconciliations.
                .collectLatest { (permission, activeLocation) ->
                    reconcileTracking(permission, activeLocation)
                }
        }

        combine(
            locationState.latestLocation,
            workLocationRepository.activeWorkLocation,
        ) { fix, activeLocation -> fix to activeLocation }
            .onEach { (fix, activeLocation) ->
                if (fix != null && activeLocation != null) {
                    proximityRepository.onLocation(fix, activeLocation.toGeofenceTarget())
                } else if (activeLocation == null) {
                    proximityRepository.reset()
                }
            }
            .launchIn(scope)
    }

    private suspend fun reconcileTracking(
        permission: LocationPermissionState,
        activeLocation: WorkLocation?,
    ) {
        trackingController.sync(permission)

        // Geofences require background access to be useful; register them only then, and only when
        // there is a location to watch. Otherwise make sure none linger.
        val useGeofences = permission.supportsBackgroundTracking && activeLocation != null
        try {
            if (useGeofences) {
                geofenceManager.register(listOf(activeLocation!!.toGeofenceTarget()))
            } else {
                geofenceManager.clear()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Play Services / device location can be unavailable; degrade to foreground proximity
            // rather than crashing.
            Log.w(TAG, "Geofence reconciliation failed", e)
        }
    }

    private companion object {
        private const val TAG = "LocationCoordinator"
    }
}
