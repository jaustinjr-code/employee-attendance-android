package com.jaustinjr.employeeattendance.location.registration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stub store of the last clock-in time per work location, keyed by [WorkLocation.id]. Stands in for
 * the real attendance backend so the location detail screen has a "last clocked in" value to show.
 * In-memory only; resets on process death.
 */
class LocationClockInRepository {

    private val _lastClockIns = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Map of work-location id to the epoch-millis timestamp of its most recent clock-in. */
    val lastClockIns: StateFlow<Map<String, Long>> = _lastClockIns.asStateFlow()

    /** Records a clock-in for [locationId] at [epochMillis] (defaults to now). */
    @Synchronized
    fun recordClockIn(locationId: String, epochMillis: Long = System.currentTimeMillis()) {
        _lastClockIns.value = _lastClockIns.value + (locationId to epochMillis)
    }
}
