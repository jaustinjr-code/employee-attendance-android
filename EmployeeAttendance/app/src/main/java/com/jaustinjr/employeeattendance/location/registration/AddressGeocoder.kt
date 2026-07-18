package com.jaustinjr.employeeattendance.location.registration

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/** A geographic point resolved from a free-form address. */
data class GeocodedPoint(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    /** A normalized, human-readable address line for display, when the platform provides one. */
    val formattedAddress: String?,
)

/**
 * Resolves a free-form address string to coordinates. This is the "enter an address" capture mode of
 * worksite registration, kept behind an interface so the registration ViewModel can be unit-tested
 * without the Android [Geocoder] and so a different geocoding backend can be swapped in later.
 */
interface AddressGeocoder {
    /** Returns the best match for [query], or null if nothing was found / geocoding is unavailable. */
    suspend fun geocode(query: String): GeocodedPoint?
}

/**
 * [AddressGeocoder] backed by the platform [Geocoder]. Availability depends on a backing service
 * being present on the device ([Geocoder.isPresent]); when absent, [geocode] returns null and the
 * UI should fall back to current-location capture.
 */
class PlatformAddressGeocoder(
    context: Context,
    private val locale: Locale = Locale.getDefault(),
) : AddressGeocoder {

    private val appContext = context.applicationContext

    override suspend fun geocode(query: String): GeocodedPoint? {
        if (query.isBlank() || !Geocoder.isPresent()) {
            Log.d(TAG, "geocode skipped (blank=${query.isBlank()} present=${Geocoder.isPresent()})")
            return null
        }
        val geocoder = Geocoder(appContext, locale)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The blocking overload is deprecated on API 33+; use the async callback variant.
                geocodeAsync(geocoder, query)
            } else {
                @Suppress("DEPRECATION")
                withContext(Dispatchers.IO) {
                    geocoder.getFromLocationName(query, 1)
                }?.firstOrNull()?.toPoint()
            }
        } catch (e: IOException) {
            // Network/backend hiccup — treat as "no result" so the caller can retry or switch modes.
            Log.w(TAG, "geocode failed for query", e)
            null
        }
    }

    private suspend fun geocodeAsync(geocoder: Geocoder, query: String): GeocodedPoint? =
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<android.location.Address>) {
                    cont.resume(addresses.firstOrNull()?.toPoint())
                }

                override fun onError(errorMessage: String?) {
                    Log.w(TAG, "geocode error: $errorMessage")
                    cont.resume(null)
                }
            })
        }

    private fun android.location.Address.toPoint(): GeocodedPoint? {
        if (!hasLatitude() || !hasLongitude()) return null
        val line = (0..maxAddressLineIndex.coerceAtLeast(-1))
            .firstOrNull()
            ?.let { getAddressLine(0) }
        return GeocodedPoint(latitude, longitude, line)
    }

    private companion object {
        const val TAG = "Geocoder"
    }
}
