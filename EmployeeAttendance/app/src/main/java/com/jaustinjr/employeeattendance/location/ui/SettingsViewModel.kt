package com.jaustinjr.employeeattendance.location.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityUpdater
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.reporting.BiweeklyReportController
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import com.jaustinjr.employeeattendance.settings.ClockNotificationSettingsStore
import com.jaustinjr.employeeattendance.settings.PrivacySettingsStore
import com.jaustinjr.employeeattendance.settings.ReportSettings
import com.jaustinjr.employeeattendance.settings.UserProfileStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs the settings screen. Exposes the account display name, the auto-clock notification behavior
 * and the reverse-geocode privacy toggle; the engines/registration flow read the same stores, so
 * changes take effect without a restart.
 */
class SettingsViewModel(
    private val settingsStore: ClockNotificationSettingsStore,
    private val privacySettingsStore: PrivacySettingsStore,
    private val userProfileStore: UserProfileStore,
    private val workLocationRepository: WorkLocationRepository,
    private val attendanceRepository: AttendanceRepository,
    private val proximityUpdater: ProximityUpdater,
    reportSettings: ReportSettings,
    private val biweeklyReportController: BiweeklyReportController,
) : ViewModel() {

    val preference: StateFlow<ClockNotificationPreference> = settingsStore.preference

    /**
     * The name shown in the attendance greeting; empty when the user hasn't set one.
     *
     * TODO: Move this to a dedicated account screen once the app bar's account entry point is
     *  built. It lives in Settings for now so the greeting name is editable at all.
     */
    val displayName: StateFlow<String> = userProfileStore.displayName

    /** Whether captured locations are reverse-geocoded to an address (a network lookup). */
    val reverseGeocodeEnabled: StateFlow<Boolean> = privacySettingsStore.reverseGeocodeEnabled

    /** Whether the biweekly report notification is scheduled. */
    val biweeklyReportEnabled: StateFlow<Boolean> = reportSettings.biweeklyNotificationEnabled

    /**
     * Callers turning this on must already hold the notification permission on Android 13+; the
     * screen requests it first, because only a composable can own the permission launcher.
     */
    fun onBiweeklyReportEnabledChanged(enabled: Boolean) {
        biweeklyReportController.setEnabled(enabled)
    }

    fun onPreferenceSelected(preference: ClockNotificationPreference) {
        settingsStore.setPreference(preference)
    }

    fun onDisplayNameChanged(name: String) {
        userProfileStore.setDisplayName(name)
    }

    fun onReverseGeocodeEnabledChanged(enabled: Boolean) {
        privacySettingsStore.setReverseGeocodeEnabled(enabled)
    }

    /**
     * Deletes all locally-stored worksites, attendance history, and proximity tracking state.
     *
     * Proximity is cleared *first, and explicitly*. Clearing the work locations does make
     * `LocationFeatureCoordinator` eventually call `reset()`, but that is an indirect side effect of
     * an unrelated reactive pipeline, not a guarantee this screen owns — and clearing proximity up
     * front also means that later `reset()` is a no-op, so no Departed event is emitted naming a
     * worksite that no longer exists. The ids being deleted are handed over so that a geofence
     * transition still in flight — the OS geofences are unregistered asynchronously — cannot write
     * a just-deleted worksite id back into the proximity store.
     */
    fun onDeleteAllData() {
        val deletedIds = workLocationRepository.workLocations.value.mapTo(mutableSetOf()) { it.id }
        proximityUpdater.clear(deletedIds)
        workLocationRepository.clearAll()
        attendanceRepository.clearAll()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                SettingsViewModel(
                    settingsStore = container.clockNotificationSettingsStore,
                    privacySettingsStore = container.privacySettingsStore,
                    userProfileStore = container.userProfileStore,
                    workLocationRepository = container.workLocationRepository,
                    attendanceRepository = container.attendanceRepository,
                    proximityUpdater = container.proximityRepository,
                    reportSettings = container.reportSettings,
                    biweeklyReportController = container.biweeklyReportController,
                )
            }
        }
    }
}
