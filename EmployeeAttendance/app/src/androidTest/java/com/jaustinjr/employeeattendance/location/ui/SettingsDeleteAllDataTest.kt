package com.jaustinjr.employeeattendance.location.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.attendance.AttendanceEvent
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.attendance.LocationAttendance
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.proximity.SharedPrefsProximityStateStore
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.settings.ClockNotificationSettingsStore
import com.jaustinjr.employeeattendance.reporting.BiweeklyReportController
import com.jaustinjr.employeeattendance.reporting.ReportScheduler
import com.jaustinjr.employeeattendance.settings.PrivacySettingsStore
import com.jaustinjr.employeeattendance.settings.ReportSettingsStore
import com.jaustinjr.employeeattendance.settings.StatusUpdateSettingsStore
import com.jaustinjr.employeeattendance.settings.UserProfileStore
import com.jaustinjr.employeeattendance.statusupdate.DefaultStatusUpdateRepository
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Device-level regression for #21: after Settings → "Delete all data", the encrypted
 * `proximity_state` store must contain no worksite id at all — not just a reset state value.
 *
 * Runs against the real [SharedPrefsProximityStateStore] (and the real settings stores) so the
 * assertion is made on what actually lands on disk.
 */
class SettingsDeleteAllDataTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeWorkLocationRepository : WorkLocationRepository {
        private val _workLocations = MutableStateFlow<List<WorkLocation>>(emptyList())
        override val workLocations: StateFlow<List<WorkLocation>> = _workLocations.asStateFlow()

        private val _activeWorkLocation = MutableStateFlow<WorkLocation?>(null)
        override val activeWorkLocation: StateFlow<WorkLocation?> = _activeWorkLocation.asStateFlow()

        override fun setActiveWorkLocation(id: String) = Unit
        override fun registerWorkLocation(location: WorkLocation) {
            _workLocations.value = listOf(location)
            _activeWorkLocation.value = location
        }
        override fun removeWorkLocation(id: String) = Unit
        override fun clearAll() {
            _workLocations.value = emptyList()
            _activeWorkLocation.value = null
        }
    }

    private class FakeAttendanceRepository : AttendanceRepository {
        private val _attendance = MutableStateFlow<Map<String, LocationAttendance>>(emptyMap())
        override val attendance: StateFlow<Map<String, LocationAttendance>> =
            _attendance.asStateFlow()
        override val eventLog: StateFlow<List<AttendanceEvent>> =
            MutableStateFlow(emptyList<AttendanceEvent>()).asStateFlow()

        override fun recordClockIn(locationId: String, epochMillis: Long, source: ClockSource) {
            _attendance.value = mapOf(locationId to LocationAttendance(lastClockInMillis = epochMillis))
        }
        override fun recordClockOut(locationId: String, epochMillis: Long, source: ClockSource) = Unit
        override fun hasClockOutEvent(locationId: String, epochMillis: Long) = false
        override fun undoEvent(locationId: String, type: ClockType, epochMillis: Long) = false
        override fun clearAll() {
            _attendance.value = emptyMap()
        }
    }

    @Before
    @After
    fun clearPrefs() {
        // SecurePreferences stores under "<name>_secure"; "proximity_state" is only the legacy
        // plaintext file that migration drains. Both must be cleared for real test isolation.
        for (name in listOf("proximity_state", "proximity_state_secure")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun deleteAllDataLeavesNoWorksiteIdInTheProximityStore() {
        val store = SharedPrefsProximityStateStore(context)
        val proximity = ProximityRepository(store)
        val workLocations = FakeWorkLocationRepository()
        val attendance = FakeAttendanceRepository()
        val reportSettings = ReportSettingsStore(context)
        val statusUpdates = DefaultStatusUpdateRepository()

        val viewModel = SettingsViewModel(
            settingsStore = ClockNotificationSettingsStore(context),
            privacySettingsStore = PrivacySettingsStore(context),
            userProfileStore = UserProfileStore(context),
            workLocationRepository = workLocations,
            attendanceRepository = attendance,
            proximityUpdater = proximity,
            reportSettings = reportSettings,
            biweeklyReportController = BiweeklyReportController(
                settings = reportSettings,
                scheduler = object : ReportScheduler {
                    override fun setBiweeklyCheckScheduled(scheduled: Boolean) = Unit
                },
                clock = Clock.systemDefaultZone(),
            ),
            statusUpdateSettingsStore = StatusUpdateSettingsStore(context),
            statusUpdateRepository = statusUpdates,
        )

        // The user is registered at, and currently inside, a worksite.
        workLocations.registerWorkLocation(
            WorkLocation(
                id = "site-to-delete",
                name = "Office",
                latitudeDegrees = 37.0,
                longitudeDegrees = -122.0,
                radiusMeters = 150f,
            ),
        )
        proximity.onGeofenceTransition("site-to-delete", ProximityState.INSIDE)
        attendance.recordClockIn("site-to-delete", epochMillis = 1_000L)
        assertEquals("site-to-delete", store.loadTargetId())
        statusUpdates.save(
            StatusUpdate(
                clockOutId = "site-to-delete@2000",
                didToday = "did today",
                plannedTomorrow = "planned tomorrow",
                couldNotDo = "could not do",
                completedAtMillis = 2_000L,
            ),
        )

        viewModel.onDeleteAllData()

        // The OS geofence for the deleted worksite is unregistered asynchronously, so a transition
        // can still be delivered after the purge. It must not resurrect the id.
        proximity.onGeofenceTransition("site-to-delete", ProximityState.INSIDE)

        // Read through a *fresh* store instance so this asserts on persisted bytes, not memory.
        val reread = SharedPrefsProximityStateStore(context)
        assertNull(reread.loadTargetId())
        assertEquals(ProximityState.UNKNOWN, reread.load())
        assertEquals(emptyList<WorkLocation>(), workLocations.workLocations.value)
        assertEquals(emptyMap<String, LocationAttendance>(), attendance.attendance.value)
        assertEquals(emptyList<StatusUpdate>(), statusUpdates.statusUpdates.value)
    }
}
