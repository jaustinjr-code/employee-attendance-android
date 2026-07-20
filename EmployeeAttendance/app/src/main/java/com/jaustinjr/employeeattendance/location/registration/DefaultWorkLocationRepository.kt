package com.jaustinjr.employeeattendance.location.registration

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent [WorkLocationRepository] backed by a Local + Remote data-source pair.
 *
 * The local store ([WorkLocationLocalDataSource]) is the source of truth: state is seeded from it on
 * construction and every mutation writes back through it, so registrations survive process death.
 * The remote source ([WorkLocationRemoteDataSource]) is a forward-looking seam — mutations are
 * mirrored to it best-effort on [ioScope] so a future backend can sync without any consumer change.
 * It is a no-op today ([StubWorkLocationRemoteDataSource]).
 *
 * Mutations are `@Synchronized` read-modify-write sequences (callable from UI, the registration
 * flow, or the coordinator), matching the concurrency contract the previous stub established.
 */
class DefaultWorkLocationRepository(
    private val local: WorkLocationLocalDataSource,
    private val remote: WorkLocationRemoteDataSource = StubWorkLocationRemoteDataSource(),
    private val ioScope: CoroutineScope,
) : WorkLocationRepository {

    private val _workLocations: MutableStateFlow<List<WorkLocation>>
    override val workLocations: StateFlow<List<WorkLocation>>

    private val _activeWorkLocation: MutableStateFlow<WorkLocation?>
    override val activeWorkLocation: StateFlow<WorkLocation?>

    init {
        val stored = local.load()
        val active = stored.locations.firstOrNull { it.id == stored.activeId }
            ?: stored.locations.firstOrNull()
        Log.d(TAG, "init: loaded ${stored.locations.size} location(s), active=${active?.id}")
        _workLocations = MutableStateFlow(stored.locations)
        workLocations = _workLocations.asStateFlow()
        _activeWorkLocation = MutableStateFlow(active)
        activeWorkLocation = _activeWorkLocation.asStateFlow()
    }

    @Synchronized
    override fun setActiveWorkLocation(id: String) {
        val target = _workLocations.value.firstOrNull { it.id == id }
        if (target == null) {
            Log.w(TAG, "setActiveWorkLocation: unknown id $id; ignoring")
            return
        }
        Log.d(TAG, "setActiveWorkLocation: $id")
        _activeWorkLocation.value = target
        persist()
    }

    @Synchronized
    override fun registerWorkLocation(location: WorkLocation) {
        Log.d(TAG, "registerWorkLocation: ${location.id} (${location.name})")
        _workLocations.value = _workLocations.value.filterNot { it.id == location.id } + location
        if (_activeWorkLocation.value == null) {
            _activeWorkLocation.value = location
        } else if (_activeWorkLocation.value?.id == location.id) {
            // Keep the active reference in sync when an active location is edited/replaced.
            _activeWorkLocation.value = location
        }
        persist()
        ioScope.launch {
            runCatching { remote.pushWorkLocation(location) }
                .onFailure { Log.w(TAG, "remote push failed for ${location.id}", it) }
        }
    }

    @Synchronized
    override fun removeWorkLocation(id: String) {
        Log.d(TAG, "removeWorkLocation: $id")
        _workLocations.value = _workLocations.value.filterNot { it.id == id }
        if (_activeWorkLocation.value?.id == id) {
            _activeWorkLocation.value = _workLocations.value.firstOrNull()
        }
        persist()
        ioScope.launch {
            runCatching { remote.deleteWorkLocation(id) }
                .onFailure { Log.w(TAG, "remote delete failed for $id", it) }
        }
    }

    @Synchronized
    override fun clearAll() {
        Log.d(TAG, "clearAll: removing all registered worksites")
        _workLocations.value = emptyList()
        _activeWorkLocation.value = null
        persist()
    }

    private fun persist() {
        local.save(_workLocations.value, _activeWorkLocation.value?.id)
    }

    private companion object {
        private const val TAG = "WorkLocRepo"
    }
}
