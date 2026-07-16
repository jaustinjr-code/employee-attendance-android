package com.jaustinjr.employeeattendance.location.tracking

import com.jaustinjr.employeeattendance.location.proximity.ProximityState

/**
 * Chooses the location request configuration adaptively so the app only spends battery on accuracy
 * and frequency when they actually matter. This is the "track only when necessary" lever for the
 * foreground-only stream (the When-In-Use case that can't lean on OS geofences).
 *
 * The proximity state drives the trade-off:
 *  - [ProximityState.UNKNOWN]: we have no fix yet, so acquire one quickly and accurately (low
 *    latency) to resolve the situation, then adapt.
 *  - [ProximityState.INSIDE]: the user is already at work; a moderate, balanced cadence is enough to
 *    confirm they're still present and to catch them leaving.
 *  - [ProximityState.OUTSIDE]: the user is away; poll slowly at low power with generous batching.
 *    An approach into the radius will be caught by the next check (or a geofence when available).
 */
object LocationPowerPolicy {

    fun foregroundConfig(proximity: ProximityState): LocationRequestConfig = when (proximity) {
        ProximityState.UNKNOWN -> LocationRequestConfig(
            priority = LocationPriority.HIGH_ACCURACY,
            intervalMillis = 5_000L,
            minUpdateIntervalMillis = 2_000L,
            maxUpdateDelayMillis = 0L,
        )

        ProximityState.INSIDE -> LocationRequestConfig(
            priority = LocationPriority.BALANCED,
            intervalMillis = 30_000L,
            minUpdateIntervalMillis = 15_000L,
            maxUpdateDelayMillis = 30_000L,
        )

        ProximityState.OUTSIDE -> LocationRequestConfig(
            priority = LocationPriority.LOW_POWER,
            intervalMillis = 60_000L,
            minUpdateIntervalMillis = 30_000L,
            maxUpdateDelayMillis = 120_000L,
        )
    }
}
