package com.jaustinjr.employeeattendance.attendance

import android.content.Context
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * On-device persistence for the attendance event log — the "Local" half of the Local + Remote pair
 * behind [AttendanceRepository]. Backed by SharedPreferences + JSON, matching the app's existing
 * persistence style.
 */
interface AttendanceLocalDataSource {
    /** Loads the persisted events, oldest first, or empty if none are stored. */
    fun load(): List<AttendanceEvent>

    /** Persists the full event log, replacing any prior state. */
    fun save(events: List<AttendanceEvent>)
}

/** [AttendanceLocalDataSource] backed by [android.content.SharedPreferences]. */
class SharedPrefsAttendanceLocalDataSource(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AttendanceLocalDataSource {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<AttendanceEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AttendanceEvent>>(raw) }
            .onFailure { Log.w(TAG, "Failed to decode attendance events; ignoring", it) }
            .getOrDefault(emptyList())
    }

    override fun save(events: List<AttendanceEvent>) {
        Log.v(TAG, "save: ${events.size} event(s)")
        prefs.edit().putString(KEY_EVENTS, json.encodeToString(events)).apply()
    }

    private companion object {
        const val TAG = "AttendStore"
        const val PREFS_NAME = "attendance_events"
        const val KEY_EVENTS = "events"
    }
}

/**
 * The "Remote" half of the pair behind [AttendanceRepository]: the seam for syncing attendance to a
 * backend. A no-op today; swapping in a real client is all that's needed to enable server sync.
 */
interface AttendanceRemoteDataSource {
    suspend fun push(event: AttendanceEvent)
    suspend fun delete(event: AttendanceEvent)
}

/** No-op [AttendanceRemoteDataSource] used while attendance is stored locally only. */
class StubAttendanceRemoteDataSource : AttendanceRemoteDataSource {
    override suspend fun push(event: AttendanceEvent) = Unit
    override suspend fun delete(event: AttendanceEvent) = Unit
}
