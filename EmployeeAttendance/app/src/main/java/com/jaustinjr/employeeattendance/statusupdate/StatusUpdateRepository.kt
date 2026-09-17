package com.jaustinjr.employeeattendance.statusupdate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists completed [StatusUpdate]s and exposes them as observable state. See
 * [DefaultStatusUpdateRepository] for the current (in-memory) implementation — a stub in the same
 * sense as `LocationClockInRepository`; see docs/architecture/overview.md §7 for what "real" looks
 * like once a backend exists.
 */
interface StatusUpdateRepository {
    /** Every completed status update, oldest first. */
    val statusUpdates: StateFlow<List<StatusUpdate>>

    /** Records a completed status update. */
    fun save(update: StatusUpdate)

    /** Deletes all recorded status updates. Backs "delete all data". */
    fun clearAll() {}
}

/**
 * In-memory [StatusUpdateRepository]; resets on process death. Mutators are `@Synchronized` by
 * this app's convention for every mutable-state repository — cheap insurance against a future
 * caller (e.g. a background sync) reaching this from more than one thread.
 */
class DefaultStatusUpdateRepository : StatusUpdateRepository {

    private val _statusUpdates = MutableStateFlow<List<StatusUpdate>>(emptyList())
    override val statusUpdates: StateFlow<List<StatusUpdate>> = _statusUpdates.asStateFlow()

    @Synchronized
    override fun save(update: StatusUpdate) {
        _statusUpdates.value = _statusUpdates.value + update
    }

    @Synchronized
    override fun clearAll() {
        _statusUpdates.value = emptyList()
    }
}
