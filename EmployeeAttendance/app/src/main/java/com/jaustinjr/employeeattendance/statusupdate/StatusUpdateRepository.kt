package com.jaustinjr.employeeattendance.statusupdate

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists completed [StatusUpdate]s and exposes them as observable state. Status updates are the
 * user's own notes, so they are kept on-device in encrypted storage and survive process death; see
 * [DefaultStatusUpdateRepository].
 */
interface StatusUpdateRepository {
    /** Every completed status update, oldest first. */
    val statusUpdates: StateFlow<List<StatusUpdate>>

    /** Records a completed status update. */
    fun save(update: StatusUpdate)

    /**
     * Replaces the answers of the status update for [clockOutId], stamping [editedAtMillis].
     *
     * @return false if no status update exists for [clockOutId].
     */
    fun updateAnswers(
        clockOutId: String,
        didToday: String,
        plannedTomorrow: String,
        couldNotDo: String,
        editedAtMillis: Long,
    ): Boolean

    /** Deletes all recorded status updates. Backs "delete all data". */
    fun clearAll() {}
}

/** On-device persistence for status updates: the storage seam behind [DefaultStatusUpdateRepository]. */
interface StatusUpdateLocalDataSource {
    /** Loads the persisted status updates, oldest first, or empty if none are stored. */
    fun load(): List<StatusUpdate>

    /** Persists the full list, replacing any prior state. */
    fun save(updates: List<StatusUpdate>)
}

/** [StatusUpdateLocalDataSource] in encrypted SharedPreferences, as JSON. */
class SharedPrefsStatusUpdateLocalDataSource(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : StatusUpdateLocalDataSource {

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    override fun load(): List<StatusUpdate> {
        val raw = prefs.getString(KEY_UPDATES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StatusUpdate>>(raw) }
            .onFailure { Log.w(TAG, "Failed to decode status updates; ignoring", it) }
            .getOrDefault(emptyList())
    }

    override fun save(updates: List<StatusUpdate>) {
        Log.v(TAG, "save: ${updates.size} status update(s)")
        prefs.edit { putString(KEY_UPDATES, json.encodeToString(updates)) }
    }

    private companion object {
        const val TAG = "StatusUpdateStore"
        const val PREFS_NAME = "status_updates"
        const val KEY_UPDATES = "updates"
    }
}

/** Non-persistent [StatusUpdateLocalDataSource], for tests and previews. */
class InMemoryStatusUpdateLocalDataSource : StatusUpdateLocalDataSource {
    private var stored: List<StatusUpdate> = emptyList()
    override fun load(): List<StatusUpdate> = stored
    override fun save(updates: List<StatusUpdate>) {
        stored = updates
    }
}

/**
 * [StatusUpdateRepository] over a [StatusUpdateLocalDataSource]: seeded from it on construction and
 * written back on every mutation. Mutators are `@Synchronized` by this app's convention for every
 * mutable-state repository.
 */
class DefaultStatusUpdateRepository(
    private val local: StatusUpdateLocalDataSource = InMemoryStatusUpdateLocalDataSource(),
) : StatusUpdateRepository {

    private val _statusUpdates = MutableStateFlow(local.load())
    override val statusUpdates: StateFlow<List<StatusUpdate>> = _statusUpdates.asStateFlow()

    @Synchronized
    override fun save(update: StatusUpdate) {
        publish(_statusUpdates.value + update)
    }

    @Synchronized
    override fun updateAnswers(
        clockOutId: String,
        didToday: String,
        plannedTomorrow: String,
        couldNotDo: String,
        editedAtMillis: Long,
    ): Boolean {
        val current = _statusUpdates.value
        val index = current.indexOfFirst { it.clockOutId == clockOutId }
        if (index < 0) return false
        val edited = current[index].copy(
            didToday = didToday,
            plannedTomorrow = plannedTomorrow,
            couldNotDo = couldNotDo,
            editedAtMillis = editedAtMillis,
        )
        publish(current.toMutableList().also { it[index] = edited })
        return true
    }

    @Synchronized
    override fun clearAll() {
        publish(emptyList())
    }

    private fun publish(updated: List<StatusUpdate>) {
        local.save(updated)
        _statusUpdates.value = updated
    }
}
