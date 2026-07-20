package com.jaustinjr.employeeattendance.location.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.registration.AddressAutocomplete
import com.jaustinjr.employeeattendance.location.registration.AddressGeocoder
import com.jaustinjr.employeeattendance.location.registration.AddressSuggestion
import com.jaustinjr.employeeattendance.location.registration.RadiusOption
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationPriority
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import com.jaustinjr.employeeattendance.location.tracking.LocationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Which capture mode the registration form is using to obtain coordinates. */
enum class CaptureMode { CURRENT, ADDRESS }

/** Transient status of an in-progress capture/geocode, driving spinners and error text. */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data object Working : CaptureStatus
    data class Error(val messageRes: Int) : CaptureStatus
}

/**
 * Form state for registering a worksite. A worksite is saveable once it has a name, a valid radius,
 * and captured coordinates (from the current position or a geocoded address).
 */
data class WorksiteRegistrationUiState(
    val name: String = "",
    val radiusOption: RadiusOption = RadiusOption.DEFAULT,
    val captureMode: CaptureMode = CaptureMode.CURRENT,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val resolvedAddress: String? = null,
    val suggestions: List<AddressSuggestion> = emptyList(),
    val status: CaptureStatus = CaptureStatus.Idle,
    val attemptedSave: Boolean = false,
    val saved: Boolean = false,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
    val radiusMeters: Float get() = radiusOption.meters

    /** In address mode the address text is itself a required field; other modes fill it implicitly. */
    private val addressRequiredButMissing: Boolean
        get() = captureMode == CaptureMode.ADDRESS && address.isBlank()

    val isValid: Boolean
        get() = name.isNotBlank() && hasCoordinates && !addressRequiredButMissing

    val canSave: Boolean
        get() = isValid && status !is CaptureStatus.Working

    // Field-level errors, surfaced only after a save attempt so the form doesn't scold the user
    // before they've finished filling it in.
    val nameError: Boolean get() = attemptedSave && name.isBlank()
    val addressError: Boolean get() = attemptedSave && addressRequiredButMissing
    val locationError: Boolean get() = attemptedSave && !hasCoordinates
}

/**
 * Drives the "Add worksite" flow. Supports two capture modes — the device's current position (via
 * [LocationTracker.currentLocation]) and a typed address (via [AddressGeocoder]) — then registers a
 * validated [WorkLocation] in the [WorkLocationRepository]. A drop-a-pin map picker is a planned
 * future third mode (tracked as a separate enhancement).
 */
class WorksiteRegistrationViewModel(
    private val workLocationRepository: WorkLocationRepository,
    private val locationTracker: LocationTracker,
    private val addressGeocoder: AddressGeocoder,
    private val addressAutocomplete: AddressAutocomplete,
    private val permissionRepository: LocationPermissionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorksiteRegistrationUiState())
    val uiState: StateFlow<WorksiteRegistrationUiState> = _uiState.asStateFlow()

    // Cached location used to bias autocomplete toward the user; fetched lazily, best-effort.
    private var biasLocation: LocationSample? = null
    private var autocompleteJob: Job? = null

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    fun onRadiusOptionChange(option: RadiusOption) =
        _uiState.update { it.copy(radiusOption = option) }

    fun onAddressChange(value: String) {
        _uiState.update {
            // Editing the address text invalidates any previously resolved point — the user must
            // tap "Find address" (or pick a suggestion) again to actually register the location.
            it.copy(
                address = value,
                latitude = null,
                longitude = null,
                resolvedAddress = null,
            )
        }
        requestSuggestions(value)
    }

    /** Applies a chosen autocomplete suggestion: fills the address and its coordinates. */
    fun onSuggestionSelected(suggestion: AddressSuggestion) {
        autocompleteJob?.cancel()
        _uiState.update {
            it.copy(
                address = suggestion.label,
                latitude = suggestion.latitudeDegrees,
                longitude = suggestion.longitudeDegrees,
                resolvedAddress = suggestion.label,
                suggestions = emptyList(),
                status = CaptureStatus.Idle,
            )
        }
    }

    fun onCaptureModeChange(mode: CaptureMode) {
        autocompleteJob?.cancel()
        _uiState.update {
            // Switching modes clears any previously captured point so the two modes can't disagree.
            it.copy(
                captureMode = mode,
                latitude = null,
                longitude = null,
                resolvedAddress = null,
                suggestions = emptyList(),
                status = CaptureStatus.Idle,
            )
        }
    }

    /**
     * Debounced address autocomplete: for queries of 3+ characters, waits briefly then fetches
     * location-biased suggestions. Shorter queries clear the list. Backed by a stub provider today
     * (see [StubAddressAutocomplete] / issue #6), so this yields no suggestions until a real places
     * API is wired in.
     */
    private fun requestSuggestions(query: String) {
        autocompleteJob?.cancel()
        // No real provider wired in: skip the whole path, including the location fix that would bias
        // it. Avoids waking the location stack (battery + privacy) to feed a no-op provider.
        if (!addressAutocomplete.isEnabled) return
        if (query.trim().length < MIN_AUTOCOMPLETE_CHARS) {
            if (_uiState.value.suggestions.isNotEmpty()) {
                _uiState.update { it.copy(suggestions = emptyList()) }
            }
            return
        }
        autocompleteJob = viewModelScope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MILLIS)
            val results = runCatching {
                addressAutocomplete.suggest(query.trim(), ensureBiasLocation())
            }.getOrDefault(emptyList()).take(MAX_SUGGESTIONS)
            _uiState.update { it.copy(suggestions = results) }
        }
    }

    private suspend fun ensureBiasLocation(): LocationSample? {
        biasLocation?.let { return it }
        if (!permissionRepository.permissionState.value.isGranted) return null
        biasLocation = runCatching {
            locationTracker.currentLocation(LocationPriority.BALANCED)
        }.getOrNull()
        return biasLocation
    }

    /** Captures the device's current position as the worksite center. Requires foreground access. */
    fun captureCurrentLocation() {
        if (!permissionRepository.refresh().isGranted) {
            _uiState.update { it.copy(status = CaptureStatus.Error(R.string.worksite_needs_permission)) }
            return
        }
        _uiState.update { it.copy(status = CaptureStatus.Working) }
        viewModelScope.launch {
            try {
                val fix = locationTracker.currentLocation(LocationPriority.HIGH_ACCURACY)
                if (fix == null) {
                    _uiState.update {
                        it.copy(status = CaptureStatus.Error(R.string.worksite_capture_failed))
                    }
                    return@launch
                }
                // Reverse-geocode the fix to the nearest building address so the worksite carries a
                // human-readable address (for future mapping/navigation), not just coordinates.
                val nearestAddress = runCatching {
                    addressGeocoder.reverseGeocode(fix.latitudeDegrees, fix.longitudeDegrees)
                }.getOrNull()
                _uiState.update {
                    it.copy(
                        latitude = fix.latitudeDegrees,
                        longitude = fix.longitudeDegrees,
                        resolvedAddress = nearestAddress,
                        status = CaptureStatus.Idle,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "current-location capture failed", e)
                _uiState.update { it.copy(status = CaptureStatus.Error(R.string.worksite_capture_failed)) }
            }
        }
    }

    /** Resolves the typed address to coordinates. */
    fun geocodeAddress() {
        val query = _uiState.value.address
        if (query.isBlank()) return
        _uiState.update { it.copy(status = CaptureStatus.Working) }
        viewModelScope.launch {
            val point = addressGeocoder.geocode(query)
            if (point == null) {
                _uiState.update { it.copy(status = CaptureStatus.Error(R.string.worksite_geocode_failed)) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    latitude = point.latitudeDegrees,
                    longitude = point.longitudeDegrees,
                    resolvedAddress = point.formattedAddress ?: query,
                    status = CaptureStatus.Idle,
                )
            }
        }
    }

    /** Registers the worksite. No-op unless [WorksiteRegistrationUiState.canSave]. */
    fun save() {
        val state = _uiState.value
        val lat = state.latitude
        val lon = state.longitude
        if (!state.canSave || lat == null || lon == null) {
            // Reveal field-level validation instead of silently doing nothing.
            Log.d(TAG, "save ignored; form incomplete")
            _uiState.update { it.copy(attemptedSave = true) }
            return
        }
        val worksite = WorkLocation(
            id = UUID.randomUUID().toString(),
            name = state.name.trim(),
            address = state.resolvedAddress
                ?: state.address.trim().takeIf { it.isNotBlank() },
            latitudeDegrees = lat,
            longitudeDegrees = lon,
            radiusMeters = state.radiusMeters,
        )
        workLocationRepository.registerWorkLocation(worksite)
        Log.d(TAG, "registered worksite ${worksite.id} (${worksite.name})")
        _uiState.update { it.copy(saved = true) }
    }

    companion object {
        private const val TAG = "WorksiteRegVM"
        private const val MIN_AUTOCOMPLETE_CHARS = 3
        private const val MAX_SUGGESTIONS = 3
        private const val AUTOCOMPLETE_DEBOUNCE_MILLIS = 300L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                WorksiteRegistrationViewModel(
                    workLocationRepository = container.workLocationRepository,
                    locationTracker = container.locationTracker,
                    addressGeocoder = container.addressGeocoder,
                    addressAutocomplete = container.addressAutocomplete,
                    permissionRepository = container.locationPermissionRepository,
                )
            }
        }
    }
}
