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

    /**
     * True once the user has reached the end of onboarding, as seen by **this process**. Never flips
     * back on its own, and [resetForNextLaunch] deliberately does not flip it either.
     */
    val completed: StateFlow<Boolean>

    /**
     * True after [resetForNextLaunch] in this process: the persisted flag is cleared, so the next
     * launch will show onboarding, while [completed] still reads true for the app that is running.
     */
    val resetPending: StateFlow<Boolean>

    fun markCompleted()

    /**
     * Makes onboarding show again on the **next** app launch. Only the persisted flag is cleared;
     * [completed] is left alone so the running app is not pulled into the carousel mid-session
     * (which would also tear down whatever screen called this). A developer action — see
     * `DeveloperToolsController.resetOnboarding`.
     */
    fun resetForNextLaunch()
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

    // Always false in a fresh process: by then the cleared flag has been read into [completed].
    private val _resetPending = MutableStateFlow(false)

    override val resetPending: StateFlow<Boolean> = _resetPending.asStateFlow()

    override fun markCompleted() {
        Log.d(TAG, "markCompleted")
        _completed.value = true
        _resetPending.value = false
        prefs.edit { putBoolean(KEY_COMPLETED, true) }
    }

    override fun resetForNextLaunch() {
        Log.d(TAG, "resetForNextLaunch")
        _resetPending.value = true
        prefs.edit { putBoolean(KEY_COMPLETED, false) }
    }

    private companion object {
        const val TAG = "OnboardingPrefs"
        const val PREFS_NAME = "onboarding"
        const val KEY_COMPLETED = "completed"
    }
}
