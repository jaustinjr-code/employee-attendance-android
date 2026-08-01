package com.jaustinjr.employeeattendance.settings

import android.content.Context
import android.util.Log
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted privacy preferences. Currently holds whether address lookup from coordinates
 * (reverse-geocoding on current-location capture) is allowed — a network call the user may want to
 * avoid. App-scoped so the Settings UI (writer) and the registration flow (reader) agree.
 */
class PrivacySettingsStore(context: Context) {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val _reverseGeocodeEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_REVERSE_GEOCODE, true),
    )

    /** Whether to reverse-geocode a captured location to a street address (a network lookup). */
    val reverseGeocodeEnabled: StateFlow<Boolean> = _reverseGeocodeEnabled.asStateFlow()

    fun setReverseGeocodeEnabled(enabled: Boolean) {
        Log.d(TAG, "setReverseGeocodeEnabled: $enabled")
        _reverseGeocodeEnabled.value = enabled
        prefs.edit().putBoolean(KEY_REVERSE_GEOCODE, enabled).apply()
    }

    private companion object {
        const val TAG = "PrivacyPrefs"
        const val PREFS_NAME = "privacy_settings"
        const val KEY_REVERSE_GEOCODE = "reverse_geocode_enabled"
    }
}
