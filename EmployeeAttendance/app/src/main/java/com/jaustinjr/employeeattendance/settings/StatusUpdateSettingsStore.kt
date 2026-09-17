package com.jaustinjr.employeeattendance.settings

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted, observable store for whether Status Update is enabled. App-scoped so the Settings UI
 * (writer) and [com.jaustinjr.employeeattendance.statusupdate.StatusUpdateCoordinator] (reader)
 * share one source of truth, and the choice survives process death. Modeled on
 * [ClockNotificationSettingsStore]; defaults to enabled, unlike that store's preference default,
 * per the feature requirement that Status Update is on unless the user turns it off.
 */
class StatusUpdateSettingsStore(context: Context) {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** Whether Status Update should run at all. Defaults to `true` when unset. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        Log.d(TAG, "setEnabled: $value")
        _enabled.value = value
        prefs.edit { putBoolean(KEY_ENABLED, value) }
    }

    private companion object {
        const val TAG = "StatusUpdatePrefs"
        const val PREFS_NAME = "status_update_settings"
        const val KEY_ENABLED = "enabled"
    }
}
