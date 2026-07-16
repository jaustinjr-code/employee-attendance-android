package com.jaustinjr.employeeattendance.location.proximity

import android.content.Context
import android.util.Log

/**
 * Persists the last known proximity state so it survives process death.
 *
 * This matters specifically for background geofencing: Android kills the app process and then cold
 * starts it *just* to deliver a geofence transition. Without persistence, an in-memory state would
 * be UNKNOWN at that moment, and an EXIT delivered after the user was INSIDE would be indistinguishable
 * from "was never inside" — swallowing the Departed event that background geofencing exists to
 * deliver.
 */
interface ProximityStateStore {
    /** The persisted proximity state, or [ProximityState.UNKNOWN] if none was saved. */
    fun load(): ProximityState

    /** The target id associated with the persisted state, or null if none. */
    fun loadTargetId(): String?

    /** Persists the current proximity [state] and its [targetId]. */
    fun save(state: ProximityState, targetId: String?)
}

/** [ProximityStateStore] backed by [android.content.SharedPreferences]. */
class SharedPrefsProximityStateStore(
    context: Context,
) : ProximityStateStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ProximityState =
        prefs.getString(KEY_STATE, null)
            ?.let { runCatching { ProximityState.valueOf(it) }.getOrNull() }
            ?: ProximityState.UNKNOWN

    override fun loadTargetId(): String? = prefs.getString(KEY_TARGET_ID, null)

    override fun save(state: ProximityState, targetId: String?) {
        Log.v(TAG, "save: state=$state target=$targetId")
        prefs.edit()
            .putString(KEY_STATE, state.name)
            .putString(KEY_TARGET_ID, targetId)
            .apply()
    }

    private companion object {
        const val TAG = "ProxStore"
        const val PREFS_NAME = "proximity_state"
        const val KEY_STATE = "state"
        const val KEY_TARGET_ID = "target_id"
    }
}
