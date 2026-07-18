package com.jaustinjr.employeeattendance.attendance

import android.util.Log
import com.jaustinjr.employeeattendance.location.proximity.ProximityEvent
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The hands-off clock engine. It is the (previously missing) consumer of the proximity layer's
 * [ProximityEvent]s: when the user crosses a worksite boundary the geofence/proximity pipeline emits
 * `Arrived`/`Departed`, and this controller turns that into an attendance record — its visibility
 * governed by the user's current [ClockNotificationSettingsStore] preference via a
 * [ClockNotificationStrategy].
 *
 * It holds no per-event state; the strategy is chosen fresh from the current [preference] each time,
 * so changing the setting takes effect on the very next arrival/departure with no restart.
 */
class AttendanceAutoClockController(
    private val proximityEvents: SharedFlow<ProximityEvent>,
    private val workLocationRepository: WorkLocationRepository,
    private val attendanceRepository: AttendanceRepository,
    private val notifier: ClockNotifications,
    private val preference: StateFlow<ClockNotificationPreference>,
) {

    /** Begins consuming proximity events on [scope]; call once with an app-lifetime scope. */
    fun start(scope: CoroutineScope) {
        Log.d(TAG, "start: consuming proximity events for auto clock in/out")
        proximityEvents
            .onEach(::handle)
            .launchIn(scope)
    }

    private fun handle(event: ProximityEvent) {
        val worksite = resolve(event.targetId)
        if (worksite == null) {
            Log.w(TAG, "no registered worksite for target ${event.targetId}; ignoring $event")
            return
        }
        val current = preference.value
        val strategy = ClockNotificationStrategy.forPreference(
            preference = current,
            attendance = attendanceRepository,
            notifier = notifier,
        )
        Log.d(TAG, "handle $event via $current")
        when (event) {
            is ProximityEvent.Arrived -> strategy.onArrived(worksite)
            is ProximityEvent.Departed -> strategy.onDeparted(worksite)
        }
    }

    private fun resolve(targetId: String): WorkLocation? =
        workLocationRepository.workLocations.value.firstOrNull { it.id == targetId }
            ?: workLocationRepository.activeWorkLocation.value?.takeIf { it.id == targetId }

    private companion object {
        private const val TAG = "AutoClock"
    }
}
