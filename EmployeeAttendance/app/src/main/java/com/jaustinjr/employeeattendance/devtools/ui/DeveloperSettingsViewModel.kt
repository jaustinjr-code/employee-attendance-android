package com.jaustinjr.employeeattendance.devtools.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.devtools.DevActionOutcome
import com.jaustinjr.employeeattendance.devtools.DeveloperSettingsStore
import com.jaustinjr.employeeattendance.devtools.DeveloperToolsController
import com.jaustinjr.employeeattendance.devtools.LogExportResult
import com.jaustinjr.employeeattendance.devtools.PermissionOverride
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.TrackingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A transient developer-facing note about the last action, addressed by string resource so the text
 * itself stays in `strings.xml`.
 *
 * @param arg optional format argument; [detail] carries text that is inherently dynamic (an
 *   exported file name, an exception message) and has no localised form.
 */
data class DevMessage(@param:StringRes val textRes: Int, val detail: String? = null)

/**
 * Everything the developer settings screen renders. Holds raw state plus the derived flags the
 * composables need, so they never re-derive policy (the same contract as `LocationUiState`).
 */
data class DeveloperSettingsUiState(
    val permissionOverride: PermissionOverride = PermissionOverride.OFF,
    val logRecipient: String = "",
    val accessLevel: LocationAccessLevel = LocationAccessLevel.NONE,
    val isPrecise: Boolean = false,
    val trackingStatus: TrackingStatus = TrackingStatus.STOPPED,
    val proximity: ProximityState = ProximityState.UNKNOWN,
    val activeWorksiteName: String? = null,
    val isClockedIn: Boolean = false,
    val isExportingLog: Boolean = false,
) {
    /** Simulations that need somewhere to arrive at are disabled until a worksite is active. */
    val canSimulateWorksiteEvents: Boolean get() = activeWorksiteName != null

    /** True when any developer override is in force, so the screen can flag the app as "not real". */
    val hasActiveOverrides: Boolean get() = permissionOverride != PermissionOverride.OFF
}

/**
 * Backs the developer settings screen: exposes the live app state a developer is trying to steer,
 * and forwards their actions to [DeveloperToolsController].
 *
 * Reached only from the debug-gated `DeveloperSettings` destination in
 * [com.jaustinjr.employeeattendance.MainActivity].
 */
class DeveloperSettingsViewModel(
    private val controller: DeveloperToolsController,
    private val settingsStore: DeveloperSettingsStore,
    permissionRepository: LocationPermissionRepository,
    locationStateRepository: LocationStateRepository,
    proximityRepository: ProximityRepository,
    workLocationRepository: WorkLocationRepository,
    attendanceRepository: AttendanceRepository,
) : ViewModel() {

    private val _isExportingLog = MutableStateFlow(false)

    private val _message = MutableStateFlow<DevMessage?>(null)

    /** The last action's result, or null once consumed by [onMessageShown]. */
    val message: StateFlow<DevMessage?> = _message

    // Split into two combines because the source count exceeds the widest typed `combine` overload.
    private val developerConfig = combine(
        settingsStore.permissionOverride,
        settingsStore.logRecipient,
        _isExportingLog,
    ) { override, recipient, exporting -> Triple(override, recipient, exporting) }

    private val appState = combine(
        permissionRepository.permissionState,
        locationStateRepository.trackingStatus,
        proximityRepository.proximity,
        workLocationRepository.activeWorkLocation,
        attendanceRepository.attendance,
    ) { permission, tracking, proximity, active, attendance ->
        val attendanceId = active?.id ?: AttendanceRepository.GENERAL_TIMECLOCK_ID
        DeveloperSettingsUiState(
            accessLevel = permission.accessLevel,
            isPrecise = permission.isPrecise,
            trackingStatus = tracking,
            proximity = proximity,
            activeWorksiteName = active?.name,
            isClockedIn = attendance[attendanceId]?.isClockedIn == true,
        )
    }

    val uiState: StateFlow<DeveloperSettingsUiState> =
        combine(developerConfig, appState) { (override, recipient, exporting), state ->
            state.copy(
                permissionOverride = override,
                logRecipient = recipient,
                isExportingLog = exporting,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DeveloperSettingsUiState(),
        )

    // ---------------------------------------------------------------- actions

    fun onPermissionOverrideSelected(override: PermissionOverride) =
        report(controller.setPermissionOverride(override))

    fun onSimulateArrival() = report(controller.simulateArrival())

    fun onSimulateDeparture() = report(controller.simulateDeparture())

    fun onClearProximity() = report(controller.clearProximity())

    fun onSimulateLocationAtWorksite() = report(controller.simulateLocationAtWorksite())

    fun onSimulateLocationAway() = report(controller.simulateLocationAwayFromWorksite())

    fun onTrackingStatusSelected(status: TrackingStatus) =
        report(controller.setTrackingStatus(status))

    fun onSeedSampleWorksite() = report(controller.seedSampleWorksite())

    fun onClearWorksites() = report(controller.clearWorksites())

    fun onForceClockIn() = report(controller.forceClockIn())

    fun onForceClockOut() = report(controller.forceClockOut())

    fun onClearAttendance() = report(controller.clearAttendance())

    fun onPostNotification(clockType: ClockType, withUndo: Boolean, confirm: Boolean) =
        report(controller.postClockNotification(clockType, withUndo, confirm))

    fun onLogRecipientChanged(address: String) = settingsStore.setLogRecipient(address)

    fun onResetDeveloperConfiguration() {
        report(controller.resetDeveloperConfiguration())
        _message.value = DevMessage(R.string.dev_reset_done)
    }

    /**
     * Reads the log and opens an email chooser. Guarded against re-entry: reading logcat takes long
     * enough on a busy device for an impatient second tap to spawn a competing export.
     */
    fun onExportLog() {
        if (_isExportingLog.value) return
        _isExportingLog.value = true
        viewModelScope.launch {
            try {
                _message.value = when (val result = controller.exportLog()) {
                    is LogExportResult.Launched ->
                        DevMessage(R.string.dev_log_export_launched, result.lineCount.toString())
                    LogExportResult.Empty -> DevMessage(R.string.dev_log_export_empty)
                    is LogExportResult.Failed ->
                        DevMessage(R.string.dev_log_export_failed, result.reason)
                }
            } finally {
                _isExportingLog.value = false
            }
        }
    }

    /** Clears the current [message] once the screen has surfaced it. */
    fun onMessageShown() {
        _message.value = null
    }

    private fun report(outcome: DevActionOutcome) {
        _message.value = when (outcome) {
            DevActionOutcome.Done -> DevMessage(R.string.dev_action_done)
            DevActionOutcome.NoActiveWorksite -> DevMessage(R.string.dev_action_needs_worksite)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                DeveloperSettingsViewModel(
                    controller = container.developerToolsController,
                    settingsStore = container.developerSettingsStore,
                    permissionRepository = container.locationPermissionRepository,
                    locationStateRepository = container.locationStateRepository,
                    proximityRepository = container.proximityRepository,
                    workLocationRepository = container.workLocationRepository,
                    attendanceRepository = container.attendanceRepository,
                )
            }
        }
    }
}
