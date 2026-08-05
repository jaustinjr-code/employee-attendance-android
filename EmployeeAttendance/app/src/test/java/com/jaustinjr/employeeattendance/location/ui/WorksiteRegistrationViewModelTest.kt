package com.jaustinjr.employeeattendance.location.ui

import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.registration.AddressAutocomplete
import com.jaustinjr.employeeattendance.location.registration.AddressGeocoder
import com.jaustinjr.employeeattendance.location.registration.AddressSuggestion
import com.jaustinjr.employeeattendance.location.registration.GeocodedPoint
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.location.tracking.LocationPriority
import com.jaustinjr.employeeattendance.location.tracking.LocationRequestConfig
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import com.jaustinjr.employeeattendance.location.tracking.LocationTracker
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorksiteRegistrationViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakePermissionRepository(granted: Boolean) : LocationPermissionRepository {
        private val level = if (granted) LocationAccessLevel.ALWAYS else LocationAccessLevel.NONE
        private val _state = MutableStateFlow(LocationPermissionState(level, isPrecise = granted))
        override val permissionState: StateFlow<LocationPermissionState> = _state
        override fun refresh(): LocationPermissionState = _state.value
    }

    private class FakeTracker(private val fix: LocationSample?) : LocationTracker {
        var currentLocationCalls = 0
        override fun locationUpdates(config: LocationRequestConfig): Flow<LocationSample> = emptyFlow()
        override suspend fun currentLocation(priority: LocationPriority): LocationSample? {
            currentLocationCalls++
            return fix
        }
    }

    private class FakeGeocoder(
        private val point: GeocodedPoint?,
        private val reverse: String? = "42 Reverse Ave",
    ) : AddressGeocoder {
        override suspend fun geocode(query: String): GeocodedPoint? = point
        override suspend fun reverseGeocode(
            latitudeDegrees: Double,
            longitudeDegrees: Double,
        ): String? = reverse
    }

    private class FakeAutocomplete(
        private val results: List<AddressSuggestion> = emptyList(),
        override val isEnabled: Boolean = true,
    ) : AddressAutocomplete {
        var lastQuery: String? = null
        override suspend fun suggest(
            query: String,
            near: com.jaustinjr.employeeattendance.location.tracking.LocationSample?,
        ): List<AddressSuggestion> {
            lastQuery = query
            return results
        }
    }

    private class RecordingWorkLocationRepository : WorkLocationRepository {
        val registered = mutableListOf<WorkLocation>()
        override val workLocations: StateFlow<List<WorkLocation>> = MutableStateFlow(emptyList())
        override val activeWorkLocation: StateFlow<WorkLocation?> = MutableStateFlow(null)
        override fun setActiveWorkLocation(id: String) = Unit
        override fun registerWorkLocation(location: WorkLocation) { registered += location }
        override fun removeWorkLocation(id: String) = Unit
    }

    private fun vm(
        repo: WorkLocationRepository = RecordingWorkLocationRepository(),
        tracker: LocationTracker = FakeTracker(SAMPLE),
        geocoder: AddressGeocoder = FakeGeocoder(POINT),
        autocomplete: AddressAutocomplete = FakeAutocomplete(),
        granted: Boolean = true,
        reverseGeocodeEnabled: StateFlow<Boolean> = MutableStateFlow(true),
    ) = WorksiteRegistrationViewModel(
        workLocationRepository = repo,
        locationTracker = tracker,
        addressGeocoder = geocoder,
        addressAutocomplete = autocomplete,
        permissionRepository = FakePermissionRepository(granted),
        reverseGeocodeEnabled = reverseGeocodeEnabled,
    )

    @Test
    fun `capturing current location sets coordinates`() = runTest {
        val model = vm()
        model.captureCurrentLocation()
        runCurrent()

        val state = model.uiState.value
        assertEquals(37.7749, state.latitude!!, 1e-6)
        assertEquals(-122.4194, state.longitude!!, 1e-6)
        assertEquals(5f, state.capturedAccuracyMeters)
        assertTrue(state.status is CaptureStatus.Idle)
    }

    @Test
    fun `capture times out when no fix arrives`() = runTest {
        val slowTracker = object : LocationTracker {
            override fun locationUpdates(config: LocationRequestConfig): Flow<LocationSample> =
                emptyFlow()
            override suspend fun currentLocation(priority: LocationPriority): LocationSample? {
                kotlinx.coroutines.delay(1_000_000) // never completes within the capture timeout
                return null
            }
        }
        val model = vm(tracker = slowTracker)

        model.captureCurrentLocation()
        advanceTimeBy(20_000) // past the 15s capture timeout
        runCurrent()

        assertTrue(model.uiState.value.status is CaptureStatus.Error)
    }

    @Test
    fun `capturing current location reverse-geocodes the nearest address`() = runTest {
        val model = vm(geocoder = FakeGeocoder(POINT, reverse = "42 Reverse Ave"))
        model.captureCurrentLocation()
        runCurrent()

        assertEquals("42 Reverse Ave", model.uiState.value.resolvedAddress)
    }

    @Test
    fun `reverse-geocode is skipped when the privacy setting is off`() = runTest {
        val model = vm(
            geocoder = FakeGeocoder(POINT, reverse = "42 Reverse Ave"),
            reverseGeocodeEnabled = MutableStateFlow(false),
        )
        model.captureCurrentLocation()
        runCurrent()

        // Coordinates captured, but no network address lookup performed.
        assertTrue(model.uiState.value.hasCoordinates)
        assertNull(model.uiState.value.resolvedAddress)
    }

    @Test
    fun `capturing without permission surfaces an error`() = runTest {
        val model = vm(granted = false)
        model.captureCurrentLocation()
        runCurrent()

        assertTrue(model.uiState.value.status is CaptureStatus.Error)
        assertFalse(model.uiState.value.hasCoordinates)
    }

    @Test
    fun `geocoding an address sets coordinates and resolved address`() = runTest {
        val model = vm()
        model.onCaptureModeChange(CaptureMode.ADDRESS)
        model.onAddressChange("123 Market St")
        model.geocodeAddress()
        runCurrent()

        val state = model.uiState.value
        assertTrue(state.hasCoordinates)
        assertEquals("123 Market St, San Francisco", state.resolvedAddress)
    }

    @Test
    fun `failed geocode surfaces an error`() = runTest {
        val model = vm(geocoder = FakeGeocoder(null))
        model.onCaptureModeChange(CaptureMode.ADDRESS)
        model.onAddressChange("nowhere")
        model.geocodeAddress()
        runCurrent()

        assertTrue(model.uiState.value.status is CaptureStatus.Error)
    }

    @Test
    fun `geocode times out when the geocoder never calls back`() = runTest {
        // Mirrors a platform Geocoder whose GeocodeListener is never invoked: the call simply
        // suspends forever. Before the timeout was added, status stayed Working indefinitely.
        val hangingGeocoder = object : AddressGeocoder {
            override suspend fun geocode(query: String): GeocodedPoint? = awaitCancellation()
            override suspend fun reverseGeocode(
                latitudeDegrees: Double,
                longitudeDegrees: Double,
            ): String? = null
        }
        val model = vm(geocoder = hangingGeocoder)
        model.onCaptureModeChange(CaptureMode.ADDRESS)
        model.onAddressChange("123 Market St")

        model.geocodeAddress()
        runCurrent()
        assertTrue("expected the spinner while the lookup is in flight",
            model.uiState.value.status is CaptureStatus.Working)

        advanceTimeBy(20_000) // past the 15s capture timeout
        runCurrent()

        val status = model.uiState.value.status
        assertTrue("expected a timeout error, got $status", status is CaptureStatus.Error)
        assertEquals(
            R.string.worksite_geocode_timeout,
            (status as CaptureStatus.Error).messageRes,
        )
    }

    @Test
    fun `a timed-out geocode leaves the form retryable and saveable again`() = runTest {
        // The point of the fix: Save is gated on `status !is Working`, so a stuck Working state
        // permanently disables it. After the timeout the user can capture a location and save.
        val hangingGeocoder = object : AddressGeocoder {
            override suspend fun geocode(query: String): GeocodedPoint? = awaitCancellation()
            override suspend fun reverseGeocode(
                latitudeDegrees: Double,
                longitudeDegrees: Double,
            ): String? = null
        }
        val model = vm(geocoder = hangingGeocoder)
        model.onNameChange("Downtown Office")
        model.onCaptureModeChange(CaptureMode.ADDRESS)
        // An otherwise complete form: name + coordinates from a picked suggestion.
        model.onSuggestionSelected(AddressSuggestion("123 Market St", 37.7749, -122.4194))
        assertTrue(model.uiState.value.canSave)

        // Re-resolving the address hangs. Without a timeout, Save is disabled for good.
        model.geocodeAddress()
        runCurrent()
        assertFalse(model.uiState.value.canSave)

        advanceTimeBy(20_000)
        runCurrent()

        assertTrue("Save must become usable again after the lookup times out",
            model.uiState.value.canSave)
    }

    @Test
    fun `a hung reverse geocode is dropped without failing the capture`() = runTest {
        // The reverse-geocode leg is optional decoration on a good fix. It used to be unbounded
        // (stranding the form in Working); it now has its own 5s sub-budget and degrades to "no
        // address" rather than spending the whole capture budget and discarding the coordinates.
        val hangingReverse = object : AddressGeocoder {
            override suspend fun geocode(query: String): GeocodedPoint? = POINT
            override suspend fun reverseGeocode(
                latitudeDegrees: Double,
                longitudeDegrees: Double,
            ): String? = awaitCancellation()
        }
        val model = vm(geocoder = hangingReverse)
        model.onNameChange("Downtown Office")

        model.captureCurrentLocation()
        advanceTimeBy(20_000)
        runCurrent()

        val state = model.uiState.value
        assertTrue("expected the capture to succeed, got ${state.status}", state.status is CaptureStatus.Idle)
        assertTrue(state.hasCoordinates)
        assertNull(state.resolvedAddress)
        assertTrue(state.canSave)
    }

    @Test
    fun `a throwing geocoder surfaces an error instead of crashing`() = runTest {
        // geocodeAddress() previously ran in a bare launch with no catch, so a geocoder throwing
        // propagated out of the coroutine.
        val throwingGeocoder = object : AddressGeocoder {
            override suspend fun geocode(query: String): GeocodedPoint? =
                throw IllegalStateException("geocoder backend unavailable")
            override suspend fun reverseGeocode(
                latitudeDegrees: Double,
                longitudeDegrees: Double,
            ): String? = null
        }
        val model = vm(geocoder = throwingGeocoder)
        model.onCaptureModeChange(CaptureMode.ADDRESS)
        model.onAddressChange("123 Market St")

        model.geocodeAddress()
        runCurrent()

        val status = model.uiState.value.status
        assertTrue("expected an error, got $status", status is CaptureStatus.Error)
        assertEquals(
            R.string.worksite_geocode_failed,
            (status as CaptureStatus.Error).messageRes,
        )
    }

    @Test
    fun `switching capture mode abandons an in-flight capture`() = runTest {
        // A stale capture must not write its result on top of the newer form state.
        val slowTracker = object : LocationTracker {
            override fun locationUpdates(config: LocationRequestConfig): Flow<LocationSample> =
                emptyFlow()
            override suspend fun currentLocation(priority: LocationPriority): LocationSample? {
                kotlinx.coroutines.delay(10_000)
                return SAMPLE
            }
        }
        val model = vm(tracker = slowTracker)

        model.captureCurrentLocation()
        runCurrent()
        assertTrue(model.uiState.value.status is CaptureStatus.Working)

        model.onCaptureModeChange(CaptureMode.ADDRESS)
        advanceTimeBy(30_000)
        runCurrent()

        val state = model.uiState.value
        // Neither the abandoned fix nor a timeout error may land on the address-mode form.
        assertFalse(state.hasCoordinates)
        assertTrue("expected no stale status, got ${state.status}", state.status is CaptureStatus.Idle)
    }

    @Test
    fun `a failing reverse geocode still yields a saveable capture`() = runTest {
        val failingReverse = object : AddressGeocoder {
            override suspend fun geocode(query: String): GeocodedPoint? = POINT
            override suspend fun reverseGeocode(
                latitudeDegrees: Double,
                longitudeDegrees: Double,
            ): String? = throw IllegalStateException("geocoder backend unavailable")
        }
        val model = vm(geocoder = failingReverse)
        model.onNameChange("Downtown Office")

        model.captureCurrentLocation()
        runCurrent()

        assertTrue(model.uiState.value.hasCoordinates)
        assertNull(model.uiState.value.resolvedAddress)
        assertTrue(model.uiState.value.canSave)
    }

    @Test
    fun `save registers a worksite once the form is complete`() = runTest {
        val repo = RecordingWorkLocationRepository()
        val model = vm(repo = repo)
        model.onNameChange("Downtown Office")
        model.captureCurrentLocation()
        runCurrent()

        assertTrue(model.uiState.value.canSave)
        model.save()

        assertEquals(1, repo.registered.size)
        val saved = repo.registered.first()
        assertEquals("Downtown Office", saved.name)
        assertEquals(150f, saved.radiusMeters)
        assertNotNull(saved.id)
        assertTrue(model.uiState.value.saved)
    }

    @Test
    fun `save is a no-op without coordinates and flags validation errors`() = runTest {
        val repo = RecordingWorkLocationRepository()
        val model = vm(repo = repo)
        model.onNameChange("No Coords")

        model.save()

        assertTrue(repo.registered.isEmpty())
        // Name is filled, but no location was captured -> location error surfaces, name does not.
        assertTrue(model.uiState.value.locationError)
        assertFalse(model.uiState.value.nameError)
    }

    @Test
    fun `save with a blank name flags the name error`() = runTest {
        val model = vm()
        model.captureCurrentLocation()
        runCurrent()

        model.save()

        assertTrue(model.uiState.value.nameError)
    }

    @Test
    fun `address mode requires a non-blank address`() = runTest {
        val model = vm()
        model.onNameChange("Site")
        model.onCaptureModeChange(CaptureMode.ADDRESS)
        // No address typed, but pretend coordinates exist by selecting a suggestion then clearing.
        model.save()

        assertTrue(model.uiState.value.addressError)
        assertFalse(model.uiState.value.canSave)
    }

    @Test
    fun `address queries of 3+ chars fetch suggestions after debounce`() = runTest {
        val suggestion = AddressSuggestion("123 Market St", 37.7749, -122.4194)
        val model = vm(autocomplete = FakeAutocomplete(listOf(suggestion)))
        model.onCaptureModeChange(CaptureMode.ADDRESS)

        model.onAddressChange("123")
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf(suggestion), model.uiState.value.suggestions)
    }

    @Test
    fun `a disabled autocomplete provider skips suggestions and the bias location fetch`() = runTest {
        val tracker = FakeTracker(SAMPLE)
        val model = vm(
            tracker = tracker,
            autocomplete = FakeAutocomplete(listOf(AddressSuggestion("x", 1.0, 2.0)), isEnabled = false),
        )
        model.onCaptureModeChange(CaptureMode.ADDRESS)

        model.onAddressChange("123 Market")
        advanceTimeBy(400)
        runCurrent()

        assertTrue(model.uiState.value.suggestions.isEmpty())
        // No GPS fix was taken to bias a provider that can't return anything.
        assertEquals(0, tracker.currentLocationCalls)
    }

    @Test
    fun `queries shorter than 3 chars produce no suggestions`() = runTest {
        val model = vm(autocomplete = FakeAutocomplete(listOf(AddressSuggestion("x", 1.0, 2.0))))
        model.onCaptureModeChange(CaptureMode.ADDRESS)

        model.onAddressChange("12")
        advanceTimeBy(400)
        runCurrent()

        assertTrue(model.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `editing the address after resolving invalidates the location`() = runTest {
        val model = vm()
        model.onCaptureModeChange(CaptureMode.ADDRESS)
        model.onAddressChange("123 Market St")
        model.geocodeAddress()
        runCurrent()
        assertTrue(model.uiState.value.hasCoordinates)

        // Typing again means the location is no longer registered until Find address is tapped.
        model.onAddressChange("123 Market Street")

        assertFalse(model.uiState.value.hasCoordinates)
    }

    @Test
    fun `selecting a suggestion fills address and coordinates`() = runTest {
        val suggestion = AddressSuggestion("123 Market St", 37.7749, -122.4194)
        val model = vm()

        model.onSuggestionSelected(suggestion)

        val state = model.uiState.value
        assertEquals("123 Market St", state.address)
        assertTrue(state.hasCoordinates)
        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun `switching capture mode clears a previous capture`() = runTest {
        val model = vm()
        model.captureCurrentLocation()
        runCurrent()
        assertTrue(model.uiState.value.hasCoordinates)

        model.onCaptureModeChange(CaptureMode.ADDRESS)

        assertFalse(model.uiState.value.hasCoordinates)
    }

    private companion object {
        val SAMPLE = LocationSample(37.7749, -122.4194, 5f, 1_000L)
        val POINT = GeocodedPoint(37.7749, -122.4194, "123 Market St, San Francisco")
    }
}
