package com.jaustinjr.employeeattendance.location.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import com.jaustinjr.employeeattendance.settings.ClockNotificationSettingsStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs the settings screen. Exposes the current [ClockNotificationPreference] and lets the user
 * change it; the auto-clock engine reads the same store, so a change takes effect on the next
 * arrival/departure without a restart.
 */
class SettingsViewModel(
    private val settingsStore: ClockNotificationSettingsStore,
) : ViewModel() {

    val preference: StateFlow<ClockNotificationPreference> = settingsStore.preference

    fun onPreferenceSelected(preference: ClockNotificationPreference) {
        settingsStore.setPreference(preference)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                SettingsViewModel(settingsStore = container.clockNotificationSettingsStore)
            }
        }
    }
}
