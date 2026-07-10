package com.jaustinjr.employeeattendance.location.proximity

import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped source of truth for the user's proximity to their work location. It is fed by two
 * producers depending on permission level:
 *
 * - Background ([android.location.Location] geofences via [GeofenceBroadcastReceiver]) call
 *   [onGeofenceTransition] when the OS reports an enter/exit.
 * - Foreground-only tracking calls [onLocation] with each fix, and this class computes the
 *   transition itself using [ProximityCalculator].
 *
 * Both paths converge here so consumers see a single [proximity] state and a single [events] stream
 * regardless of which producer is active. Transitions emit [ProximityEvent]s — the seam an
 * attendance/clock-in system consumes.
 *
 * @param exitBufferMeters hysteresis band for the foreground evaluator (see [ProximityCalculator]).
 */
class ProximityRepository(
    private val exitBufferMeters: Float = DEFAULT_EXIT_BUFFER_METERS,
) {

    private val _proximity = MutableStateFlow(ProximityState.UNKNOWN)
    val proximity: StateFlow<ProximityState> = _proximity.asStateFlow()

    private val _events = MutableSharedFlow<ProximityEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
    )
    val events: SharedFlow<ProximityEvent> = _events.asSharedFlow()

    /** Feed from OS geofence transitions (background path). */
    fun onGeofenceTransition(targetId: String, state: ProximityState) {
        setState(state, targetId)
    }

    /** Feed from the foreground location stream; computes the transition with hysteresis. */
    fun onLocation(sample: LocationSample, target: GeofenceTarget) {
        val distance = ProximityCalculator.distanceMeters(sample, target)
        val next = ProximityCalculator.evaluate(
            current = _proximity.value,
            distanceMeters = distance,
            radiusMeters = target.radiusMeters,
            exitBufferMeters = exitBufferMeters,
        )
        setState(next, target.id)
    }

    /** Clears proximity, e.g. when tracking stops or no target is registered. */
    fun reset() {
        _proximity.value = ProximityState.UNKNOWN
    }

    private fun setState(next: ProximityState, targetId: String) {
        val previous = _proximity.value
        if (next == previous) return
        _proximity.value = next
        when (next) {
            ProximityState.INSIDE -> _events.tryEmit(ProximityEvent.Arrived(targetId))
            // Only a genuine inside -> outside move is a "departure"; leaving UNKNOWN is not.
            ProximityState.OUTSIDE ->
                if (previous == ProximityState.INSIDE) {
                    _events.tryEmit(ProximityEvent.Departed(targetId))
                }
            ProximityState.UNKNOWN -> Unit
        }
    }

    companion object {
        private const val DEFAULT_EXIT_BUFFER_METERS = 50f
        private const val EVENT_BUFFER = 8
    }
}
