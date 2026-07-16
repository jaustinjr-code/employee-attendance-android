package com.jaustinjr.employeeattendance.location.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.LocationClockInRepository
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationPowerPolicy
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationTracker
import com.jaustinjr.employeeattendance.location.tracking.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the location displays retrofitted into the app. This single object drives every
 * location element on the screen — the setup chip's appearance, the pill, the proximity status, and
 * whether the map is shown — so the composables stay stateless and previewable.
 *
 * @param activeWorkLocation the location being tracked, or null if none is registered.
 * @param proximity whether the user is at that location.
 * @param trackingStatus the current tracking mode (used to reason about background vs foreground).
 * @param accessLevel the granted location access, driving chip appearance and detail visibility.
 * @param lastClockInEpochMillis the most recent clock-in for the active location, or null if none.
 */
data class LocationUiState(
    val activeWorkLocation: WorkLocation? = null,
    val proximity: ProximityState = ProximityState.UNKNOWN,
    val trackingStatus: TrackingStatus = TrackingStatus.STOPPED,
    val accessLevel: LocationAccessLevel = LocationAccessLevel.NONE,
    val lastClockInEpochMillis: Long? = null,
) {
    /** Whether any location access is granted. */
    val isGranted: Boolean get() = accessLevel.isGranted

    /** True under When-In-Use access, so the UI can show the reduced-experience note. */
    val isDegraded: Boolean get() = accessLevel.isDegraded

    /** True once location is set up (access granted and a location registered): show the pill. */
    val isSetUp: Boolean get() = isGranted && activeWorkLocation != null

    /**
     * Whether the map may be shown: only under full "Allow all the time" access and with a
     * registered location. The map lives on the detail screen, gated on this.
     */
    val canShowMap: Boolean get() = accessLevel.supportsBackgroundTracking && activeWorkLocation != null
}

/**
 * Backs the home/dashboard location displays and, crucially, supplies the *foreground* half of the
 * tracking pipeline: while this ViewModel is alive (a location screen is on-screen) and permission
 * is granted, it collects foreground fixes and publishes them to [LocationStateRepository]. The
 * [com.jaustinjr.employeeattendance.location.LocationFeatureCoordinator] turns those into proximity.
 * This is what keeps the feature working under When-In-Use access, where no background service runs.
 */
class LocationViewModel(
    private val workLocationRepository: WorkLocationRepository,
    private val proximityRepository: ProximityRepository,
    private val locationStateRepository: LocationStateRepository,
    private val permissionRepository: LocationPermissionRepository,
    private val locationTracker: LocationTracker,
    private val clockInRepository: LocationClockInRepository,
) : ViewModel() {

    val uiState: StateFlow<LocationUiState> = combine(
        workLocationRepository.activeWorkLocation,
        proximityRepository.proximity,
        locationStateRepository.trackingStatus,
        permissionRepository.permissionState,
        clockInRepository.lastClockIns,
    ) { activeLocation, proximity, trackingStatus, permission, clockIns ->
        LocationUiState(
            activeWorkLocation = activeLocation,
            proximity = proximity,
            trackingStatus = trackingStatus,
            accessLevel = permission.accessLevel,
            lastClockInEpochMillis = activeLocation?.let { clockIns[it.id] },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LocationUiState(),
    )

    init {
        collectForegroundFixesWhenDegraded()
    }

    /**
     * Records a clock-in against the currently active work location, if any. Wired to the clock-in
     * action so the location detail screen can show "last clocked in" for that location.
     */
    fun onClockIn() {
        val active = workLocationRepository.activeWorkLocation.value
        Log.d(TAG, "onClockIn: activeLocation=${active?.id}")
        active?.let { clockInRepository.recordClockIn(it.id) }
    }

    /**
     * Supplies foreground fixes only under When-In-Use access. Under full (ALWAYS) access the
     * background service and OS geofences already produce fixes and proximity, so running a second
     * foreground stream there would waste battery — hence this collector is gated off in that case.
     *
     * The request config is chosen adaptively from the current proximity via [LocationPowerPolicy],
     * and collectLatest restarts the stream when the gate or the appropriate cadence changes.
     */
    private fun collectForegroundFixesWhenDegraded() {
        viewModelScope.launch {
            combine(
                permissionRepository.permissionState.map { it.isDegraded },
                proximityRepository.proximity,
            ) { degraded, proximity -> degraded to proximity }
                .distinctUntilChanged()
                .collectLatest { (degraded, proximity) ->
                    if (!degraded) {
                        Log.v(TAG, "foreground collection idle (not degraded)")
                        return@collectLatest
                    }
                    Log.d(TAG, "starting foreground collection (proximity=$proximity)")
                    try {
                        locationTracker.locationUpdates(LocationPowerPolicy.foregroundConfig(proximity))
                            .collect(locationStateRepository::publishLocation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Location may be momentarily unavailable; ignore and let the next
                        // state change or ViewModel recreation retry.
                    }
                }
        }
    }

    companion object {
        private const val TAG = "LocVM"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container =
                    (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                LocationViewModel(
                    workLocationRepository = container.workLocationRepository,
                    proximityRepository = container.proximityRepository,
                    locationStateRepository = container.locationStateRepository,
                    permissionRepository = container.locationPermissionRepository,
                    locationTracker = container.locationTracker,
                    clockInRepository = container.locationClockInRepository,
                )
            }
        }
    }
}
