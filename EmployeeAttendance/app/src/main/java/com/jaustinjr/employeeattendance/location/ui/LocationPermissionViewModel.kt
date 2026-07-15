package com.jaustinjr.employeeattendance.location.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
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
    val foregroundPermanentlyDenied: Boolean = false,
) {
    /** Whether to surface the persistent "limited access" degraded-mode notice. */
    val showDegradedNotice: Boolean get() = permission.isDegraded

    /**
     * Whether the "Enable" action must route to app settings rather than the system dialog: the
     * user permanently denied foreground location, so the OS would silently auto-deny another
     * runtime request.
     */
    val requiresSettingsForForeground: Boolean
        get() = !permission.isGranted && foregroundPermanentlyDenied
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
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // Dismissals and the permanent-denial flag are persisted in SavedStateHandle so they survive
    // process death; otherwise the rationale would resurface on every cold start even after the user
    // said "Maybe Later" or permanently denied the permission.
    private val dismissedPrompts = MutableStateFlow(loadDismissedPrompts())
    private val foregroundPermanentlyDenied =
        MutableStateFlow(savedState[KEY_FOREGROUND_DENIED] ?: false)

    val uiState: StateFlow<LocationPermissionUiState> =
        combine(
            repository.permissionState,
            dismissedPrompts,
            foregroundPermanentlyDenied,
        ) { permission, dismissed, permanentlyDenied ->
            LocationPermissionUiState(
                permission = permission,
                visiblePrompt = computePrompt(permission, dismissed),
                foregroundPermanentlyDenied = permanentlyDenied,
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
        val state = repository.refresh()
        // If access was granted (e.g. the user relented in Settings), clear the permanent-denial
        // flag so the normal runtime-request flow is offered again next time.
        if (state.isGranted && foregroundPermanentlyDenied.value) {
            foregroundPermanentlyDenied.value = false
            savedState[KEY_FOREGROUND_DENIED] = false
        }
    }

    /**
     * Records the outcome of a foreground permission request that did NOT grant access.
     *
     * @param canRetryWithRationale the value of shouldShowRequestPermissionRationale after the
     *   denial. When false, the OS will no longer show the runtime dialog (a permanent denial), so
     *   the only way forward is app settings.
     */
    fun onForegroundDenied(canRetryWithRationale: Boolean) {
        if (!canRetryWithRationale) {
            foregroundPermanentlyDenied.value = true
            savedState[KEY_FOREGROUND_DENIED] = true
        }
    }

    /**
     * The user explicitly asked to set up location (e.g. tapped the "Setup Location" chip). Clears
     * any prior "Maybe Later" dismissals so the appropriate rationale — the initial enable prompt,
     * the background upgrade, or (if permanently denied) the settings path — surfaces again. If the
     * required access is already fully granted, [computePrompt] yields null and nothing is shown.
     */
    fun onSetupRequested() {
        if (dismissedPrompts.value.isNotEmpty()) {
            dismissedPrompts.value = emptySet()
            savedState[KEY_DISMISSED] = ArrayList<String>()
        }
    }

    /** User chose "Maybe Later" on the given prompt; suppress it (persisted across process death). */
    fun onPromptDismissed(prompt: LocationPermissionPrompt) {
        val updated = dismissedPrompts.value + prompt
        dismissedPrompts.value = updated
        savedState[KEY_DISMISSED] = ArrayList(updated.map { it.name })
    }

    private fun loadDismissedPrompts(): Set<LocationPermissionPrompt> =
        savedState.get<ArrayList<String>>(KEY_DISMISSED)
            ?.mapNotNull { name -> runCatching { LocationPermissionPrompt.valueOf(name) }.getOrNull() }
            ?.toSet()
            ?: emptySet()

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val KEY_DISMISSED = "dismissed_prompts"
        private const val KEY_FOREGROUND_DENIED = "foreground_permanently_denied"

        /** Factory that pulls the permission repository from the application container. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EmployeeAttendanceApplication
                LocationPermissionViewModel(
                    repository = app.container.locationPermissionRepository,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}
