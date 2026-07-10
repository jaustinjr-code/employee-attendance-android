package com.jaustinjr.employeeattendance.location.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.permission.LocationPermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Which rationale prompt, if any, should currently be shown to the user.
 */
enum class LocationPermissionPrompt {
    /** No permission yet: show the initial "Enable Auto Clock-In?" rationale. */
    EnableForeground,

    /**
     * Foreground ("While Using App") is granted but background is not: offer to upgrade to
     * "Allow all the time", explaining that the current access is degraded. Only shown when the
     * platform actually has a separate background grant to request.
     */
    UpgradeToAlways,
}

/**
 * UI state for the location permission flow.
 *
 * @param permission the latest known permission state.
 * @param visiblePrompt the rationale dialog to display, or null if none should be shown.
 */
data class LocationPermissionUiState(
    val permission: LocationPermissionState = LocationPermissionState.Denied,
    val visiblePrompt: LocationPermissionPrompt? = null,
) {
    /** Whether to surface the persistent "limited access" degraded-mode notice. */
    val showDegradedNotice: Boolean get() = permission.isDegraded
}

/**
 * Drives the location permission rationale flow. It combines the system permission state (from
 * [LocationPermissionRepository]) with per-session "dismissed" flags to decide which, if any,
 * rationale dialog to show. The ViewModel holds no Android UI references and performs no permission
 * requests itself — the composable layer owns the system launchers and calls [onPermissionResult]
 * when they return.
 */
class LocationPermissionViewModel(
    private val repository: LocationPermissionRepository,
) : ViewModel() {

    /** Prompts the user dismissed this session; suppressed until the process is restarted. */
    private val dismissedPrompts = MutableStateFlow<Set<LocationPermissionPrompt>>(emptySet())

    val uiState: StateFlow<LocationPermissionUiState> =
        combine(repository.permissionState, dismissedPrompts) { permission, dismissed ->
            LocationPermissionUiState(
                permission = permission,
                visiblePrompt = computePrompt(permission, dismissed),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = LocationPermissionUiState(repository.permissionState.value),
        )

    private fun computePrompt(
        permission: LocationPermissionState,
        dismissed: Set<LocationPermissionPrompt>,
    ): LocationPermissionPrompt? = when {
        !permission.isGranted && LocationPermissionPrompt.EnableForeground !in dismissed ->
            LocationPermissionPrompt.EnableForeground

        // Only nudge toward background access when the platform models it as a separate grant;
        // below API 29 a foreground grant already implies ALWAYS.
        permission.isDegraded &&
            LocationPermissions.backgroundPermissionExists &&
            LocationPermissionPrompt.UpgradeToAlways !in dismissed ->
            LocationPermissionPrompt.UpgradeToAlways

        else -> null
    }

    /** Re-reads the current permission state; call on lifecycle resume and after any request. */
    fun onPermissionResult() {
        repository.refresh()
    }

    /** User chose "Maybe Later" on the given prompt; suppress it for the rest of the session. */
    fun onPromptDismissed(prompt: LocationPermissionPrompt) {
        dismissedPrompts.value = dismissedPrompts.value + prompt
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Factory that pulls the permission repository from the application container. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EmployeeAttendanceApplication
                LocationPermissionViewModel(app.container.locationPermissionRepository)
            }
        }
    }
}
