package com.jaustinjr.employeeattendance.location.registration

import android.content.Context
import android.util.Log
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

private const val DECODE_TAG = "WorkLocStore"

/**
 * Decodes the persisted worksite list **one entry at a time**, keeping every entry that decodes and
 * dropping only those that don't.
 *
 * Issue #25: this used to be a single `decodeFromString<List<WorkLocation>>`. Because
 * [WorkLocation]'s `init { require(...) }` validation runs per element during deserialization, one
 * bad entry threw for the whole array and the fallback returned an empty list — a user with three
 * registered worksites lost all three because of one. Decoding element-by-element keeps the "an
 * invalid WorkLocation can never be constructed" guarantee at the per-entry level, where it belongs,
 * instead of applying it to the entire list.
 *
 * If the payload isn't a JSON array at all there is nothing to salvage, so that still yields an
 * empty list. Nothing here throws: a corrupt store must not crash startup.
 *
 * Privacy: failures are logged with the entry's *index* and the exception, never the raw element —
 * these entries contain the user's worksite coordinates and must not be echoed into logcat.
 */
internal fun decodeWorkLocations(json: Json, raw: String): List<WorkLocation> {
    val elements = runCatching { json.parseToJsonElement(raw).jsonArray }
        .onFailure { e -> Log.w(DECODE_TAG, "Stored work locations are not a JSON array; ignoring", e) }
        .getOrNull() ?: return emptyList()

    val locations = ArrayList<WorkLocation>(elements.size)
    var dropped = 0
    elements.forEachIndexed { index, element ->
        runCatching { json.decodeFromJsonElement<WorkLocation>(element) }
            .onSuccess { locations += it }
            .onFailure { e ->
                dropped++
                Log.w(DECODE_TAG, "Dropping invalid stored work location at index $index", e)
            }
    }
    if (dropped > 0) {
        Log.w(DECODE_TAG, "Dropped $dropped of ${elements.size} stored work locations; kept ${locations.size}")
    }
    return locations
}

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
        val locations = raw?.let { decodeWorkLocations(json, it) } ?: emptyList()
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
