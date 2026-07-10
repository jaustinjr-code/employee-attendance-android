package com.jaustinjr.employeeattendance.location.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the app's current location permission state.
 *
 * Android does not push permission changes to the app, and the user can revoke permissions from
 * system settings while the app is backgrounded. The state is therefore recomputed on demand via
 * [refresh], which callers should invoke on lifecycle resume and after any permission request
 * completes.
 */
interface LocationPermissionRepository {

    /** The most recently observed permission state. Updated by [refresh]. */
    val permissionState: StateFlow<LocationPermissionState>

    /** Re-reads the granted permissions from the system and emits an updated [permissionState]. */
    fun refresh(): LocationPermissionState
}

/**
 * [LocationPermissionRepository] backed by the platform [PackageManager] grant checks.
 *
 * Uses the application context so it can safely outlive any single Activity.
 */
class SystemLocationPermissionRepository(
    context: Context,
) : LocationPermissionRepository {

    private val appContext = context.applicationContext

    private val _permissionState = MutableStateFlow(readCurrentState())
    override val permissionState: StateFlow<LocationPermissionState> = _permissionState.asStateFlow()

    override fun refresh(): LocationPermissionState =
        readCurrentState().also { _permissionState.value = it }

    private fun readCurrentState(): LocationPermissionState {
        val fineGranted = isGranted(LocationPermissions.FINE)
        val coarseGranted = isGranted(LocationPermissions.COARSE)
        val foregroundGranted = fineGranted || coarseGranted

        if (!foregroundGranted) {
            return LocationPermissionState.Denied
        }

        val accessLevel = when {
            // Before Android 10 there is no separate background permission: a foreground grant
            // already permits background location use.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> LocationAccessLevel.ALWAYS
            isGranted(LocationPermissions.BACKGROUND) -> LocationAccessLevel.ALWAYS
            else -> LocationAccessLevel.WHEN_IN_USE
        }

        return LocationPermissionState(accessLevel = accessLevel, isPrecise = fineGranted)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
}
