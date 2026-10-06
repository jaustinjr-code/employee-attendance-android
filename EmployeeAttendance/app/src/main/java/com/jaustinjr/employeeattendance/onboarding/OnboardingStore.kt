package com.jaustinjr.employeeattendance.onboarding

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has finished the first-launch onboarding carousel. The interface is the test
 * seam; [SharedPrefsOnboardingStore] is the persisted implementation.
 */
interface OnboardingStore {

    /** True once the user has reached the end of onboarding. Never flips back on its own. */
    val completed: StateFlow<Boolean>

    fun markCompleted()
}

/**
 * [OnboardingStore] backed by [SecurePreferences], like every other store in the app. The flag
 * itself is not sensitive, but going through the same factory keeps backup exclusion
 * (`BackupRulesTest`) and the startup forcing in `EmployeeAttendanceApplication` uniform rather
 * than special-casing one plaintext file.
 */
class SharedPrefsOnboardingStore(context: Context) : OnboardingStore {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val _completed = MutableStateFlow(prefs.getBoolean(KEY_COMPLETED, false))

    override val completed: StateFlow<Boolean> = _completed.asStateFlow()

    override fun markCompleted() {
        Log.d(TAG, "markCompleted")
        _completed.value = true
        prefs.edit { putBoolean(KEY_COMPLETED, true) }
    }

    private companion object {
        const val TAG = "OnboardingPrefs"
        const val PREFS_NAME = "onboarding"
        const val KEY_COMPLETED = "completed"
    }
}
