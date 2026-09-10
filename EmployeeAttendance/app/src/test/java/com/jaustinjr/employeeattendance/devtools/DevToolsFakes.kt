package com.jaustinjr.employeeattendance.devtools

import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockNotifications
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.attendance.LocationAttendance
import com.jaustinjr.employeeattendance.devtools.facade.DevAttendanceFacade
import com.jaustinjr.employeeattendance.devtools.facade.DevNotificationPreview
import com.jaustinjr.employeeattendance.devtools.facade.DevWorksiteFacade
import com.jaustinjr.employeeattendance.devtools.facade.RepositoryDevWorksiteFacade
import com.jaustinjr.employeeattendance.devtools.facade.SandboxedDevNotificationPreview
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.proximity.ProximityStateStore
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared in-memory doubles for the developer-tools tests. The controller is deliberately wired to
 * the app's real repositories in production, so these stand in for exactly those seams and record
 * what the controller asked them to do.
 */

class FakeDeveloperSettingsStore : DeveloperSettingsStore {
    private val _permissionOverride = MutableStateFlow(PermissionOverride.OFF)
    override val permissionOverride: StateFlow<PermissionOverride> = _permissionOverride

    private val _logRecipient = MutableStateFlow("")
    override val logRecipient: StateFlow<String> = _logRecipient

    var resetCount = 0
        private set

    override fun setPermissionOverride(override: PermissionOverride) {
        _permissionOverride.value = override
    }

    override fun setLogRecipient(address: String) {
        _logRecipient.value = address.trim()
    }

    override fun reset() {
        resetCount++
        _permissionOverride.value = PermissionOverride.OFF
        _logRecipient.value = ""
    }
}

class FakePermissionRepository(
    initial: LocationPermissionState = LocationPermissionState.Denied,
) : LocationPermissionRepository {
    private val _state = MutableStateFlow(initial)
    override val permissionState: StateFlow<LocationPermissionState> = _state

    var refreshCount = 0
        private set

    override fun refresh(): LocationPermissionState {
        refreshCount++
        return _state.value
    }
}

class FakeWorkLocationRepository(initial: List<WorkLocation> = emptyList()) : WorkLocationRepository {
    private val _locations = MutableStateFlow(initial)
    override val workLocations: StateFlow<List<WorkLocation>> = _locations

    private val _active = MutableStateFlow(initial.firstOrNull())
    override val activeWorkLocation: StateFlow<WorkLocation?> = _active

    override fun setActiveWorkLocation(id: String) {
        _locations.value.firstOrNull { it.id == id }?.let { _active.value = it }
    }

    override fun registerWorkLocation(location: WorkLocation) {
        _locations.value = _locations.value.filterNot { it.id == location.id } + location
        if (_active.value == null) _active.value = location
    }

    override fun removeWorkLocation(id: String) {
        _locations.value = _locations.value.filterNot { it.id == id }
        if (_active.value?.id == id) _active.value = _locations.value.firstOrNull()
    }

    override fun clearAll() {
        _locations.value = emptyList()
        _active.value = null
    }
}

/** Records the append-only log the same way `DefaultAttendanceRepository` does, without persistence. */
class FakeAttendanceRepository : AttendanceRepository {
    val events = mutableListOf<AttendanceEvent>()

    private val _attendance = MutableStateFlow<Map<String, LocationAttendance>>(emptyMap())
    override val attendance: StateFlow<Map<String, LocationAttendance>> = _attendance

    override fun recordClockIn(locationId: String, epochMillis: Long, source: ClockSource) {
        events += AttendanceEvent(locationId, ClockType.CLOCK_IN, epochMillis, source)
        recompute()
    }

    override fun recordClockOut(locationId: String, epochMillis: Long, source: ClockSource) {
        events += AttendanceEvent(locationId, ClockType.CLOCK_OUT, epochMillis, source)
        recompute()
    }

    /** Mirrors the real repository: only the latest event, and only if type and time both match. */
    override fun undoEvent(locationId: String, type: ClockType, epochMillis: Long): Boolean {
        val index = events.indexOfLast { it.locationId == locationId }
        val named = index >= 0 &&
            events[index].type == type && events[index].epochMillis == epochMillis
        if (!named) return false
        events.removeAt(index)
        recompute()
        return true
    }

    override fun clearAll() {
        events.clear()
        recompute()
    }

    override fun clearBySource(source: ClockSource) {
        events.removeAll { it.source == source }
        recompute()
    }

    private fun recompute() {
        _attendance.value = events.groupBy { it.locationId }.mapValues { (_, forLocation) ->
            LocationAttendance(
                lastClockInMillis = forLocation.filter { it.type == ClockType.CLOCK_IN }
                    .maxOfOrNull { it.epochMillis },
                lastClockOutMillis = forLocation.filter { it.type == ClockType.CLOCK_OUT }
                    .maxOfOrNull { it.epochMillis },
                lastClockOutManual = forLocation.lastOrNull { it.type == ClockType.CLOCK_OUT }
                    ?.source == ClockSource.MANUAL,
            )
        }
    }
}

class RecordingClockNotifications : ClockNotifications {
    data class Recorded(val worksite: WorkLocation, val event: AttendanceEvent, val withUndo: Boolean)
    data class Confirm(val worksite: WorkLocation, val clockType: ClockType)
    data class Cancelled(val worksite: WorkLocation, val clockType: ClockType)

    val recorded = mutableListOf<Recorded>()
    val confirms = mutableListOf<Confirm>()
    val cancelled = mutableListOf<Cancelled>()

    override fun notifyRecorded(worksite: WorkLocation, event: AttendanceEvent, withUndo: Boolean) {
        recorded += Recorded(worksite, event, withUndo)
    }

    override fun notifyConfirm(worksite: WorkLocation, clockType: ClockType) {
        confirms += Confirm(worksite, clockType)
    }

    override fun cancel(worksite: WorkLocation, clockType: ClockType) {
        cancelled += Cancelled(worksite, clockType)
    }
}

/** In-memory [ProximityStateStore] so a real `ProximityRepository` can be used in JVM tests. */
class FakeProximityStateStore(
    private var state: ProximityState = ProximityState.UNKNOWN,
    private var targetId: String? = null,
) : ProximityStateStore {
    override fun load(): ProximityState = state
    override fun loadTargetId(): String? = targetId
    override fun save(state: ProximityState, targetId: String?) {
        this.state = state
        this.targetId = targetId
    }
}

class FakeDeveloperLogExporter(
    private var result: LogExportResult = LogExportResult.Launched("log.txt", 12),
) : DeveloperLogExporter {
    var lastRecipient: String? = null
        private set
    var lastHeader: String? = null
        private set
    var callCount = 0
        private set

    fun willReturn(result: LogExportResult) {
        this.result = result
    }

    override suspend fun exportToEmail(recipient: String, header: String): LogExportResult {
        callCount++
        lastRecipient = recipient
        lastHeader = header
        return result
    }
}

/**
 * Records what the developer tools asked the attendance seam to do. Backed by a
 * [FakeAttendanceRepository] so the facade's own narrowing (provenance tag, no undo) is what is
 * under test rather than a hand-written stub of it.
 */
class FakeDevAttendanceFacade(
    val repository: FakeAttendanceRepository = FakeAttendanceRepository(),
) : DevAttendanceFacade {
    val events get() = repository.events
    var clearSimulatedCount = 0
        private set
    var clearAllCount = 0
        private set

    override fun recordSimulatedClockIn(locationId: String, epochMillis: Long) =
        repository.recordClockIn(locationId, epochMillis, ClockSource.SIMULATED)

    override fun recordSimulatedClockOut(locationId: String, epochMillis: Long) =
        repository.recordClockOut(locationId, epochMillis, ClockSource.SIMULATED)

    override fun clearSimulatedAttendance() {
        clearSimulatedCount++
        repository.clearBySource(ClockSource.SIMULATED)
    }

    override fun clearAllAttendance() {
        clearAllCount++
        repository.clearAll()
    }

    override fun isClockedIn(locationId: String): Boolean =
        repository.attendance.value[locationId]?.isClockedIn == true
}

/** [DevWorksiteFacade] over a [FakeWorkLocationRepository], so seeding/removal is really applied. */
class FakeDevWorksiteFacade(
    val repository: FakeWorkLocationRepository = FakeWorkLocationRepository(),
    private val sampleWorksiteName: String = "Dev Sample Worksite",
) : DevWorksiteFacade {
    private val delegate = RepositoryDevWorksiteFacade(repository, sampleWorksiteName)

    var removeAllCount = 0
        private set
    var removeSampleCount = 0
        private set

    override val activeWorksite: StateFlow<WorkLocation?> get() = delegate.activeWorksite
    override val registeredCount: Int get() = delegate.registeredCount

    override fun seedSampleWorksite(latitudeDegrees: Double, longitudeDegrees: Double) =
        delegate.seedSampleWorksite(latitudeDegrees, longitudeDegrees)

    override fun removeSampleWorksite() {
        removeSampleCount++
        delegate.removeSampleWorksite()
    }

    override fun removeAllWorksites() {
        removeAllCount++
        delegate.removeAllWorksites()
    }
}

/** Records the previews the controller asked for, *after* the sandbox id swap has been applied. */
class RecordingDevNotificationPreview(
    val notifications: RecordingClockNotifications = RecordingClockNotifications(),
) : DevNotificationPreview {
    private val delegate = SandboxedDevNotificationPreview(notifications)

    val recorded get() = notifications.recorded
    val confirms get() = notifications.confirms

    override fun previewRecordedNotification(
        worksite: WorkLocation,
        clockType: ClockType,
        withUndo: Boolean,
    ) = delegate.previewRecordedNotification(worksite, clockType, withUndo)

    override fun previewConfirmNotification(worksite: WorkLocation, clockType: ClockType) =
        delegate.previewConfirmNotification(worksite, clockType)
}
