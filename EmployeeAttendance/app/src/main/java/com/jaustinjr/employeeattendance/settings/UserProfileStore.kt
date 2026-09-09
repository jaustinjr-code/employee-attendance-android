package com.jaustinjr.employeeattendance.settings

import android.content.Context
import android.util.Log
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted, observable store for the account details the user can edit themselves. Currently just
 * the display name used in the attendance greeting. App-scoped so the Settings UI (writer) and the
 * attendance screen (reader) share one source of truth.
 *
 * The name is stored exactly as typed — trimming happens where it is displayed — so the text field
 * doesn't fight the user mid-word. An empty value means "not set"; callers fall back to a default.
 */
class UserProfileStore(context: Context) {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val _displayName = MutableStateFlow(prefs.getString(KEY_DISPLAY_NAME, "").orEmpty())

    /** The user's display name, or an empty string when they haven't set one. */
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    fun setDisplayName(name: String) {
        Log.d(TAG, "setDisplayName: ${name.length} chars")
        _displayName.value = name
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    private companion object {
        const val TAG = "UserProfile"
        const val PREFS_NAME = "user_profile"
        const val KEY_DISPLAY_NAME = "display_name"
    }
}
