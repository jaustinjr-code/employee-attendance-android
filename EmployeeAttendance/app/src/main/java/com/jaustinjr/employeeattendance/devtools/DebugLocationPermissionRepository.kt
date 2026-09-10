package com.jaustinjr.employeeattendance.devtools

import android.util.Log
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Decorates the real [LocationPermissionRepository] so a developer can pin the app to any permission
 * state without touching system settings.
 *
 * It sits at the *repository* seam deliberately: every consumer — the coordinator's tracking and
 * geofence reconciliation, both ViewModels, the tracking service's pre-check — reads permission from
 * here, so an override exercises the real production reactions to a permission change rather than
 * only repainting the UI. That also means an override to `ALWAYS` without the underlying grant will
 * make the app *attempt* background tracking and be refused by the platform; that refusal path is
 * itself worth being able to trigger, and the app degrades to `FOREGROUND_ONLY` as designed.
 *
 * Installed only in debug builds; release wiring binds the system repository directly.
 *
 * @param delegate the real, system-backed repository.
 * @param override the developer's current choice; [PermissionOverride.OFF] passes [delegate] through.
 * @param scope app-lifetime scope the combined state is shared on. Eagerly started so a consumer
 *   reading `.value` before it subscribes still sees the override applied.
 */
class DebugLocationPermissionRepository(
    private val delegate: LocationPermissionRepository,
    private val override: StateFlow<PermissionOverride>,
    scope: CoroutineScope,
) : LocationPermissionRepository {

    override val permissionState: StateFlow<LocationPermissionState> =
        combine(delegate.permissionState, override) { real, chosen -> effective(real, chosen) }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = effective(delegate.permissionState.value, override.value),
            )

    /**
     * Refreshes the real grant (so turning the override back off immediately reflects reality) and
     * returns the *effective* state, matching what [permissionState] will emit.
     */
    override fun refresh(): LocationPermissionState =
        effective(delegate.refresh(), override.value)

    private fun effective(
        real: LocationPermissionState,
        chosen: PermissionOverride,
    ): LocationPermissionState {
        val forced = chosen.toState() ?: return real
        Log.d(TAG, "permission overridden: real=$real forced=$forced")
        return forced
    }

    private companion object {
        const val TAG = "DebugPermRepo"
    }
}
