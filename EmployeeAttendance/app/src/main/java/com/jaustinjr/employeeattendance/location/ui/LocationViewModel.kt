package com.jaustinjr.employeeattendance.location.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationRequestConfig
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
 * UI state for the location displays retrofitted into the app.
 *
 * @param activeWorkLocation the location being tracked, or null if none is registered.
 * @param proximity whether the user is at that location.
 * @param trackingStatus the current tracking mode (used to reason about background vs foreground).
 * @param isDegraded true when access is When-In-Use, so the UI can show the reduced-experience note.
 */
data class LocationUiState(
    val activeWorkLocation: WorkLocation? = null,
    val proximity: ProximityState = ProximityState.UNKNOWN,
    val trackingStatus: TrackingStatus = TrackingStatus.STOPPED,
    val isDegraded: Boolean = false,
)

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
) : ViewModel() {

    val uiState: StateFlow<LocationUiState> = combine(
        workLocationRepository.activeWorkLocation,
        proximityRepository.proximity,
        locationStateRepository.trackingStatus,
        permissionRepository.permissionState,
    ) { activeLocation, proximity, trackingStatus, permission ->
        LocationUiState(
            activeWorkLocation = activeLocation,
            proximity = proximity,
            trackingStatus = trackingStatus,
            isDegraded = permission.isDegraded,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LocationUiState(),
    )

    init {
        collectForegroundFixesWhilePermitted()
    }

    /**
     * Streams foreground fixes into the shared state repository whenever location permission is
     * held. Uses distinctUntilChanged on the granted flag so the collector is torn down/recreated
     * only when access is gained or lost, not on every permission emission.
     */
    private fun collectForegroundFixesWhilePermitted() {
        viewModelScope.launch {
            permissionRepository.permissionState
                .map { it.isGranted }
                .distinctUntilChanged()
                // collectLatest so losing permission cancels the (infinite) inner update stream.
                .collectLatest { granted ->
                    if (!granted) return@collectLatest
                    try {
                        locationTracker.locationUpdates(LocationRequestConfig.Foreground)
                            .collect(locationStateRepository::publishLocation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Location may be momentarily unavailable; ignore and let the next
                        // permission change or ViewModel recreation retry.
                    }
                }
        }
    }

    companion object {
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
                )
            }
        }
    }
}
