package com.jaustinjr.employeeattendance.devtools

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The *sticky* developer overrides — the ones that must keep applying across process death, because
 * a developer sets them and then exercises the app normally (including cold starts from a geofence
 * broadcast).
 *
 * One-shot developer actions (simulate an arrival, post a notification, seed a worksite) are not
 * stored here: they act directly on the app's real repositories through [DeveloperToolsController],
 * so what the developer sees afterwards is genuine app state rather than a parallel mock of it.
 *
 * Interface plus default implementation, matching `ProximityStateStore`: the interface is the seam
 * that keeps [DeveloperToolsController] unit-testable without `SharedPreferences`.
 */
interface DeveloperSettingsStore {

    /** Forces the app's observed location permission state; [PermissionOverride.OFF] defers to the system. */
    val permissionOverride: StateFlow<PermissionOverride>

    /** Address the log export is pre-addressed to. Empty means "let the mail app ask". */
    val logRecipient: StateFlow<String>

    fun setPermissionOverride(override: PermissionOverride)

    fun setLogRecipient(address: String)

    /**
     * Returns every developer override to its default. This is the persistence half of the
     * "Reset developer configuration" action; [DeveloperToolsController.resetDeveloperConfiguration]
     * also unwinds the simulated runtime state the overrides produced.
     */
    fun reset()
}

/**
 * [DeveloperSettingsStore] backed by the same [SecurePreferences] the production stores use, under
 * its own file so [reset] can clear developer state without touching anything the user owns.
 *
 * Only ever constructed for debug builds — see [com.jaustinjr.employeeattendance.di.DefaultAppContainer].
 */
class SharedPrefsDeveloperSettingsStore(context: Context) : DeveloperSettingsStore {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val _permissionOverride =
        MutableStateFlow(PermissionOverride.fromName(prefs.getString(KEY_PERMISSION_OVERRIDE, null)))
    override val permissionOverride: StateFlow<PermissionOverride> = _permissionOverride.asStateFlow()

    private val _logRecipient =
        MutableStateFlow(prefs.getString(KEY_LOG_RECIPIENT, null).orEmpty())
    override val logRecipient: StateFlow<String> = _logRecipient.asStateFlow()

    override fun setPermissionOverride(override: PermissionOverride) {
        Log.d(TAG, "setPermissionOverride: $override")
        _permissionOverride.value = override
        prefs.edit { putString(KEY_PERMISSION_OVERRIDE, override.name) }
    }

    override fun setLogRecipient(address: String) {
        val trimmed = address.trim()
        Log.d(TAG, "setLogRecipient: present=${trimmed.isNotEmpty()}")
        _logRecipient.value = trimmed
        prefs.edit { putString(KEY_LOG_RECIPIENT, trimmed) }
    }

    override fun reset() {
        Log.d(TAG, "reset: clearing developer overrides")
        _permissionOverride.value = PermissionOverride.OFF
        _logRecipient.value = ""
        prefs.edit { clear() }
    }

    private companion object {
        const val TAG = "DevPrefs"
        const val PREFS_NAME = "developer_settings"
        const val KEY_PERMISSION_OVERRIDE = "permission_override"
        const val KEY_LOG_RECIPIENT = "log_recipient"
    }
}
