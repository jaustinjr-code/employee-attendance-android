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
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import com.jaustinjr.employeeattendance.settings.ClockNotificationSettingsStore
import com.jaustinjr.employeeattendance.settings.PrivacySettingsStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs the settings screen. Exposes the auto-clock notification behavior and the reverse-geocode
 * privacy toggle; the engines/registration flow read the same stores, so changes take effect without
 * a restart.
 */
class SettingsViewModel(
    private val settingsStore: ClockNotificationSettingsStore,
    private val privacySettingsStore: PrivacySettingsStore,
    private val workLocationRepository: WorkLocationRepository,
    private val attendanceRepository: AttendanceRepository,
    private val proximityUpdater: ProximityUpdater,
) : ViewModel() {

    val preference: StateFlow<ClockNotificationPreference> = settingsStore.preference

    /** Whether captured locations are reverse-geocoded to an address (a network lookup). */
    val reverseGeocodeEnabled: StateFlow<Boolean> = privacySettingsStore.reverseGeocodeEnabled

    fun onPreferenceSelected(preference: ClockNotificationPreference) {
        settingsStore.setPreference(preference)
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
     * worksite that no longer exists.
     */
    fun onDeleteAllData() {
        proximityUpdater.clear()
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
                    workLocationRepository = container.workLocationRepository,
                    attendanceRepository = container.attendanceRepository,
                    proximityUpdater = container.proximityRepository,
                )
            }
        }
    }
}
