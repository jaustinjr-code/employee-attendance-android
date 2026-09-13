package com.jaustinjr.employeeattendance.settings

import android.content.Context
import android.util.Log
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The biweekly report opt-in and the bookkeeping the worker needs to post each summary once.
 * An interface so the ViewModel and worker decisions are unit-testable.
 */
interface ReportSettings {
    /** Whether the user opted in to the biweekly report notification. Off by default. */
    val biweeklyNotificationEnabled: StateFlow<Boolean>

    fun setBiweeklyNotificationEnabled(enabled: Boolean)

    /** Start date of the last biweekly period a notification was handled for, posted or skipped. */
    var lastNotifiedPeriodStart: LocalDate?
}

/** [ReportSettings] persisted in [SecurePreferences]. */
class ReportSettingsStore(context: Context) : ReportSettings {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    override val biweeklyNotificationEnabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setBiweeklyNotificationEnabled(enabled: Boolean) {
        Log.d(TAG, "setBiweeklyNotificationEnabled: $enabled")
        _enabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override var lastNotifiedPeriodStart: LocalDate?
        get() = prefs.getString(KEY_LAST_NOTIFIED, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(value) {
            prefs.edit().putString(KEY_LAST_NOTIFIED, value?.toString()).apply()
        }

    private companion object {
        const val TAG = "ReportPrefs"
        const val PREFS_NAME = "report_settings"
        const val KEY_ENABLED = "biweekly_notification_enabled"
        const val KEY_LAST_NOTIFIED = "last_notified_period_start"
    }
}
