package com.jaustinjr.employeeattendance.settings

import android.content.Context
import android.util.Log
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted, observable store for the user's [ClockNotificationPreference]. App-scoped so the
 * Settings UI (writer) and the auto-clock engine (reader) share one source of truth, and the choice
 * survives process death. Backed by SharedPreferences, matching the app's persistence style.
 */
class ClockNotificationSettingsStore(context: Context) {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val _preference = MutableStateFlow(load())

    /** The current preference; updated immediately when [setPreference] is called. */
    val preference: StateFlow<ClockNotificationPreference> = _preference.asStateFlow()

    fun setPreference(value: ClockNotificationPreference) {
        Log.d(TAG, "setPreference: $value")
        _preference.value = value
        prefs.edit().putString(KEY_PREFERENCE, value.name).apply()
    }

    private fun load(): ClockNotificationPreference =
        prefs.getString(KEY_PREFERENCE, null)
            ?.let { runCatching { ClockNotificationPreference.valueOf(it) }.getOrNull() }
            ?: ClockNotificationPreference.DEFAULT

    private companion object {
        const val TAG = "ClockPrefs"
        const val PREFS_NAME = "clock_notification_settings"
        const val KEY_PREFERENCE = "preference"
    }
}
