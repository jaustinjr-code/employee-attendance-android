package com.jaustinjr.employeeattendance.location.registration

import android.content.Context
import android.util.Log
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A snapshot of what the local store holds: the registered locations and which id is active. */
data class StoredWorkLocations(
    val locations: List<WorkLocation>,
    val activeId: String?,
)

/**
 * On-device persistence for registered work locations. This is the "Local" half of the Local +
 * Remote repository pair behind [WorkLocationRepository]; [DefaultWorkLocationRepository] treats it
 * as the source of truth so registrations survive process death and app restarts.
 */
interface WorkLocationLocalDataSource {
    /** Loads the persisted locations + active id, or an empty snapshot if nothing is stored. */
    fun load(): StoredWorkLocations

    /** Persists [locations] and the [activeId] selection, replacing any prior state. */
    fun save(locations: List<WorkLocation>, activeId: String?)
}

/**
 * [WorkLocationLocalDataSource] backed by [android.content.SharedPreferences], serializing the list
 * as JSON with `kotlinx.serialization`. Mirrors the persistence approach already used for proximity
 * state ([com.jaustinjr.employeeattendance.location.proximity.SharedPrefsProximityStateStore]), so
 * the app keeps a single, dependency-light persistence style (no Room/DataStore).
 */
class SharedPrefsWorkLocationLocalDataSource(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : WorkLocationLocalDataSource {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    override fun load(): StoredWorkLocations {
        val raw = prefs.getString(KEY_LOCATIONS, null)
        val locations = raw?.let {
            // A corrupt/incompatible payload must not crash startup: fall back to empty and log.
            runCatching { json.decodeFromString<List<WorkLocation>>(it) }
                .onFailure { e -> Log.w(TAG, "Failed to decode stored work locations; ignoring", e) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        val activeId = prefs.getString(KEY_ACTIVE_ID, null)
            // Guard against a dangling active id that no longer maps to a stored location.
            ?.takeIf { id -> locations.any { it.id == id } }
        return StoredWorkLocations(locations, activeId)
    }

    override fun save(locations: List<WorkLocation>, activeId: String?) {
        Log.v(TAG, "save: ${locations.size} location(s), active=$activeId")
        prefs.edit()
            .putString(KEY_LOCATIONS, json.encodeToString(locations))
            .putString(KEY_ACTIVE_ID, activeId)
            .apply()
    }

    private companion object {
        const val TAG = "WorkLocStore"
        const val PREFS_NAME = "work_locations"
        const val KEY_LOCATIONS = "locations"
        const val KEY_ACTIVE_ID = "active_id"
    }
}
