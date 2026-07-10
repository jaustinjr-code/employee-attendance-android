package com.jaustinjr.employeeattendance.location.tracking

import com.google.android.gms.location.Priority

/**
 * Accuracy/power trade-off for a location request, mapped to the Fused Location Provider priorities.
 * Ordered from most accurate/most power-hungry to least.
 */
enum class LocationPriority {
    /** GPS-level accuracy. Highest power cost; reserve for when a precise fix is genuinely needed. */
    HIGH_ACCURACY,

    /** ~City-block accuracy from Wi-Fi/cell. A good default that avoids waking the GPS radio. */
    BALANCED,

    /** Coarse, low-power fixes suitable for periodic background checks. */
    LOW_POWER,

    /** No power of our own: only receive fixes other apps request. */
    PASSIVE,
    ;

    fun toGmsPriority(): Int = when (this) {
        HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
        BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LOW_POWER -> Priority.PRIORITY_LOW_POWER
        PASSIVE -> Priority.PRIORITY_PASSIVE
    }
}

/**
 * Configuration for a stream of location updates.
 *
 * The interval fields are hints to the Fused Location Provider, which batches and coalesces work
 * across apps to save power. [maxUpdateDelayMillis] in particular allows the OS to defer and batch
 * deliveries, trading latency for battery — the primary lever for efficient background tracking.
 *
 * @param priority accuracy/power trade-off (see [LocationPriority]).
 * @param intervalMillis desired interval between updates.
 * @param minUpdateIntervalMillis fastest interval the app can handle if fixes arrive early (e.g.
 *   because another app requested a faster rate). Must be <= [intervalMillis].
 * @param maxUpdateDelayMillis largest acceptable batching delay; larger values save more power.
 */
data class LocationRequestConfig(
    val priority: LocationPriority,
    val intervalMillis: Long,
    val minUpdateIntervalMillis: Long,
    val maxUpdateDelayMillis: Long,
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        require(minUpdateIntervalMillis in 0..intervalMillis) {
            "minUpdateIntervalMillis must be in 0..intervalMillis"
        }
        require(maxUpdateDelayMillis >= 0) { "maxUpdateDelayMillis must be non-negative" }
    }

    companion object {
        /**
         * Low-latency, high-accuracy updates for when the app is in the foreground and the user is
         * actively looking at location-driven UI. Power-tuned further in the power-efficiency task.
         */
        val Foreground = LocationRequestConfig(
            priority = LocationPriority.HIGH_ACCURACY,
            intervalMillis = 10_000L,
            minUpdateIntervalMillis = 5_000L,
            maxUpdateDelayMillis = 0L,
        )

        /**
         * Periodic, batched, balanced-power updates for background tracking. Longer interval and a
         * generous batching window let the OS coalesce fixes to conserve battery.
         */
        val Background = LocationRequestConfig(
            priority = LocationPriority.BALANCED,
            intervalMillis = 60_000L,
            minUpdateIntervalMillis = 30_000L,
            maxUpdateDelayMillis = 120_000L,
        )
    }
}
