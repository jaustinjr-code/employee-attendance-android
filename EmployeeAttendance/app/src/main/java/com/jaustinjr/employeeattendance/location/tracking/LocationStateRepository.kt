package com.jaustinjr.employeeattendance.location.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** High-level description of what the tracking pipeline is currently doing. */
enum class TrackingStatus {
    /** Not tracking (no permission, or tracking has not been started). */
    STOPPED,

    /** Tracking only while the app is in the foreground, due to When-In-Use permission. Degraded. */
    FOREGROUND_ONLY,

    /** Full background tracking is active via the foreground service. */
    BACKGROUND_ACTIVE,
}

/**
 * App-scoped holder for the most recent location fix and the current [TrackingStatus].
 *
 * This is the shared hand-off point between the *producers* of location data (the foreground
 * service, or a foreground-only collector) and its *consumers* (proximity detection, UI). Keeping
 * it separate from the [LocationTracker] source means consumers observe a single latest value
 * regardless of which producer is active.
 */
class LocationStateRepository {

    private val _latestLocation = MutableStateFlow<LocationSample?>(null)
    val latestLocation: StateFlow<LocationSample?> = _latestLocation.asStateFlow()

    private val _trackingStatus = MutableStateFlow(TrackingStatus.STOPPED)
    val trackingStatus: StateFlow<TrackingStatus> = _trackingStatus.asStateFlow()

    fun publishLocation(sample: LocationSample) {
        _latestLocation.value = sample
    }

    fun updateStatus(status: TrackingStatus) {
        _trackingStatus.value = status
    }
}
