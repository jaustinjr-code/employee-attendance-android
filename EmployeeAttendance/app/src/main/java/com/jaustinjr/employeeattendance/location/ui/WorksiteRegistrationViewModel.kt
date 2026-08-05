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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Horizontal accuracy of a current-location capture, so the user can judge/retry it. */
    val capturedAccuracyMeters: Float? = null,
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
    private val reverseGeocodeEnabled: StateFlow<Boolean>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        // Without location permission, current-location capture can't work — start on the address
        // mode so a user who granted no location can still register a worksite (geocoding only needs
        // network, not location).
        WorksiteRegistrationUiState(
            captureMode = if (permissionRepository.permissionState.value.isGranted) {
                CaptureMode.CURRENT
            } else {
                CaptureMode.ADDRESS
            },
        ),
    )
    val uiState: StateFlow<WorksiteRegistrationUiState> = _uiState.asStateFlow()

    // Cached location used to bias autocomplete toward the user; fetched lazily, best-effort.
    private var biasLocation: LocationSample? = null
    private var autocompleteJob: Job? = null

    // The in-flight capture/geocode, tracked so a stale result can't land on top of newer form
    // state (e.g. the user switches capture mode while a 15s location fix is still pending).
    private var captureJob: Job? = null

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    fun onRadiusOptionChange(option: RadiusOption) =
        _uiState.update { it.copy(radiusOption = option) }

    fun onAddressChange(value: String) {
        cancelCapture()
        _uiState.update {
            // Editing the address text invalidates any previously resolved point — the user must
            // tap "Find address" (or pick a suggestion) again to actually register the location.
            it.copy(
                address = value,
                latitude = null,
                longitude = null,
                resolvedAddress = null,
                capturedAccuracyMeters = null,
            )
        }
        requestSuggestions(value)
    }

    /** Applies a chosen autocomplete suggestion: fills the address and its coordinates. */
    fun onSuggestionSelected(suggestion: AddressSuggestion) {
        autocompleteJob?.cancel()
        cancelCapture()
        _uiState.update {
            it.copy(
                address = suggestion.label,
                latitude = suggestion.latitudeDegrees,
                longitude = suggestion.longitudeDegrees,
                resolvedAddress = suggestion.label,
                capturedAccuracyMeters = null,
                suggestions = emptyList(),
                status = CaptureStatus.Idle,
            )
        }
    }

    fun onCaptureModeChange(mode: CaptureMode) {
        autocompleteJob?.cancel()
        cancelCapture()
        _uiState.update {
            // Switching modes clears any previously captured point so the two modes can't disagree.
            it.copy(
                captureMode = mode,
                latitude = null,
                longitude = null,
                resolvedAddress = null,
                capturedAccuracyMeters = null,
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
            val results = tryOrNull("address autocomplete") {
                addressAutocomplete.suggest(query.trim(), ensureBiasLocation())
            }.orEmpty().take(MAX_SUGGESTIONS)
            _uiState.update { it.copy(suggestions = results) }
        }
    }

    private suspend fun ensureBiasLocation(): LocationSample? {
        biasLocation?.let { return it }
        if (!permissionRepository.permissionState.value.isGranted) return null
        biasLocation = tryOrNull("autocomplete bias location") {
            locationTracker.currentLocation(LocationPriority.BALANCED)
        }
        return biasLocation
    }

    /**
     * Runs a best-effort suspending call, logging and returning null on failure. Unlike
     * `runCatching`, cancellation (including [TimeoutCancellationException]) always propagates, so
     * this can be used safely inside [launchBoundedCapture].
     */
    private suspend fun <T> tryOrNull(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logFailure(what, e)
        null
    }

    /**
     * Logs a failure by exception type only. Platform geocoder exceptions embed the coordinates or
     * address they were called with in their message, and this is a location app — the type is
     * enough to debug with, and keeping the message out of logcat keeps the user's whereabouts out
     * of it too.
     */
    private fun logFailure(what: String, e: Throwable) {
        Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
    }

    /** Cancels an in-flight capture and clears the spinner it owns. */
    private fun cancelCapture() {
        val job = captureJob ?: return
        captureJob = null
        job.cancel()
        // The cancelled job will never write a terminal status, so clear Working here or the form
        // would stay gated (canSave requires status !is Working) with nothing left to un-gate it.
        _uiState.update {
            if (it.status is CaptureStatus.Working) it.copy(status = CaptureStatus.Idle) else it
        }
    }

    /**
     * Shared driver for the capture actions that gate the form via [CaptureStatus].
     *
     * [produce] is bounded by [CAPTURE_TIMEOUT_MILLIS] because every provider it wraps (the fused
     * location client, the platform [android.location.Geocoder] listener API) can in principle never
     * call back. Without the bound, `status` would stay [CaptureStatus.Working] forever — spinner up,
     * Save permanently disabled (see [WorksiteRegistrationUiState.canSave]), no error, no retry.
     * A null result means "nothing found" and reports [failureMessageRes].
     */
    private fun <T : Any> launchBoundedCapture(
        operation: String,
        timeoutMessageRes: Int,
        failureMessageRes: Int,
        produce: suspend () -> T?,
        onSuccess: (T) -> Unit,
    ) {
        cancelCapture()
        _uiState.update { it.copy(status = CaptureStatus.Working) }
        captureJob = viewModelScope.launch {
            try {
                val result = withTimeout(CAPTURE_TIMEOUT_MILLIS) { produce() }
                if (result == null) {
                    _uiState.update { it.copy(status = CaptureStatus.Error(failureMessageRes)) }
                    return@launch
                }
                onSuccess(result)
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "$operation timed out")
                _uiState.update { it.copy(status = CaptureStatus.Error(timeoutMessageRes)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(operation, e)
                _uiState.update { it.copy(status = CaptureStatus.Error(failureMessageRes)) }
            }
        }
    }

    /** Captures the device's current position as the worksite center. Requires foreground access. */
    fun captureCurrentLocation() {
        if (!permissionRepository.refresh().isGranted) {
            _uiState.update { it.copy(status = CaptureStatus.Error(R.string.worksite_needs_permission)) }
            return
        }
        launchBoundedCapture(
            operation = "current-location capture",
            timeoutMessageRes = R.string.worksite_capture_timeout,
            failureMessageRes = R.string.worksite_capture_failed,
            produce = produce@{
                // A worksite center doesn't need GPS-grade precision, so BALANCED avoids waking the
                // GPS radio at full power. The captured accuracy is surfaced so the user can retry.
                val fix = locationTracker.currentLocation(LocationPriority.BALANCED)
                    ?: return@produce null
                // Reverse-geocode the fix to the nearest building address so the worksite carries a
                // human-readable address (for future mapping/navigation), not just coordinates. This
                // is a network lookup, so honor the user's privacy setting to disable it. Neither a
                // failure nor a hang here is fatal — the coordinates alone are enough to save the
                // worksite — so it gets its own smaller budget that degrades to "no address"
                // instead of spending the outer budget and throwing the good fix away.
                val nearestAddress = if (reverseGeocodeEnabled.value) {
                    withTimeoutOrNull(REVERSE_GEOCODE_TIMEOUT_MILLIS) {
                        tryOrNull("reverse geocode") {
                            addressGeocoder.reverseGeocode(fix.latitudeDegrees, fix.longitudeDegrees)
                        }
                    }
                } else {
                    null
                }
                fix to nearestAddress
            },
        ) { (fix, nearestAddress) ->
            _uiState.update {
                it.copy(
                    latitude = fix.latitudeDegrees,
                    longitude = fix.longitudeDegrees,
                    resolvedAddress = nearestAddress,
                    capturedAccuracyMeters = fix.accuracyMeters,
                    status = CaptureStatus.Idle,
                )
            }
        }
    }

    /** Resolves the typed address to coordinates. */
    fun geocodeAddress() {
        val query = _uiState.value.address
        if (query.isBlank()) return
        launchBoundedCapture(
            operation = "address geocode",
            timeoutMessageRes = R.string.worksite_geocode_timeout,
            failureMessageRes = R.string.worksite_geocode_failed,
            produce = { addressGeocoder.geocode(query) },
        ) { point ->
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
        private const val CAPTURE_TIMEOUT_MILLIS = 15_000L

        /**
         * Sub-budget for the optional reverse-geocode leg of a current-location capture. Shorter
         * than [CAPTURE_TIMEOUT_MILLIS] and non-fatal: exceeding it drops the human-readable
         * address, it does not fail the capture.
         */
        private const val REVERSE_GEOCODE_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                WorksiteRegistrationViewModel(
                    workLocationRepository = container.workLocationRepository,
                    locationTracker = container.locationTracker,
                    addressGeocoder = container.addressGeocoder,
                    addressAutocomplete = container.addressAutocomplete,
                    permissionRepository = container.locationPermissionRepository,
                    reverseGeocodeEnabled = container.privacySettingsStore.reverseGeocodeEnabled,
                )
            }
        }
    }
}
