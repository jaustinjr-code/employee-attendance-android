package com.jaustinjr.employeeattendance.location.registration

import com.jaustinjr.employeeattendance.location.tracking.LocationSample

/** A single autocomplete suggestion: a display label plus the coordinates it resolves to. */
data class AddressSuggestion(
    val label: String,
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
)

/**
 * Suggests addresses as the user types in the worksite registration flow. Kept behind an interface
 * so the UI/ViewModel can wire up the (debounced, 3-character-minimum) autocomplete experience
 * against a stub now and a real places provider later.
 */
interface AddressAutocomplete {
    /**
     * Returns up to a few suggestions for [query], biased toward [near] (the user's current location)
     * so the closest matches rank first. Callers pass only queries of 3+ characters.
     */
    suspend fun suggest(query: String, near: LocationSample?): List<AddressSuggestion>
}

/**
 * Placeholder [AddressAutocomplete] that returns no suggestions.
 *
 * TODO(#6): Replace with a real location-biased places/autocomplete provider (e.g. Google Places
 *   Autocomplete). It must prefix-match [query] (3+ chars), bias by [near] to surface the closest
 *   matches, and return the top 3 with coordinates. See issue #6 for API requirements. The UI and
 *   ViewModel already consume this interface, so only this implementation needs to change.
 */
class StubAddressAutocomplete : AddressAutocomplete {
    override suspend fun suggest(query: String, near: LocationSample?): List<AddressSuggestion> =
        emptyList()
}
