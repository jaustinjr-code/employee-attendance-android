package com.jaustinjr.employeeattendance.location.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.attendance.LocationAttendance
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.SharedPrefsProximityStateStore
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import com.jaustinjr.employeeattendance.settings.ClockNotificationSettingsStore
import com.jaustinjr.employeeattendance.settings.PrivacySettingsStore
import com.jaustinjr.employeeattendance.settings.StatusUpdateSettingsStore
import com.jaustinjr.employeeattendance.settings.UserProfileStore
import com.jaustinjr.employeeattendance.statusupdate.DefaultStatusUpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Device-level test for the Settings "Status updates" toggle: it is shown, on by default, and a
 * tap actually persists through [StatusUpdateSettingsStore] (SharedPreferences), which the JVM
 * unit-test config cannot verify — see docs/maintenance/testing.md.
 */
class SettingsStatusUpdateToggleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeWorkLocationRepository : WorkLocationRepository {
        private val _workLocations = MutableStateFlow<List<WorkLocation>>(emptyList())
        override val workLocations: StateFlow<List<WorkLocation>> = _workLocations.asStateFlow()
        private val _activeWorkLocation = MutableStateFlow<WorkLocation?>(null)
        override val activeWorkLocation: StateFlow<WorkLocation?> = _activeWorkLocation.asStateFlow()
        override fun setActiveWorkLocation(id: String) = Unit
        override fun registerWorkLocation(location: WorkLocation) = Unit
        override fun removeWorkLocation(id: String) = Unit
        override fun clearAll() = Unit
    }

    private class FakeAttendanceRepository : AttendanceRepository {
        private val _attendance = MutableStateFlow<Map<String, LocationAttendance>>(emptyMap())
        override val attendance: StateFlow<Map<String, LocationAttendance>> =
            _attendance.asStateFlow()
        override fun recordClockIn(locationId: String, epochMillis: Long, source: ClockSource) = Unit
        override fun recordClockOut(locationId: String, epochMillis: Long, source: ClockSource) = Unit
        override fun hasClockOutEvent(locationId: String, epochMillis: Long) = false
        override fun undoEvent(locationId: String, type: ClockType, epochMillis: Long) = false
        override fun clearAll() = Unit
    }

    @Before
    @After
    fun clearPrefs() {
        for (name in listOf("status_update_settings", "status_update_settings_secure")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun viewModel() = SettingsViewModel(
        settingsStore = ClockNotificationSettingsStore(context),
        privacySettingsStore = PrivacySettingsStore(context),
        userProfileStore = UserProfileStore(context),
        workLocationRepository = FakeWorkLocationRepository(),
        attendanceRepository = FakeAttendanceRepository(),
        proximityUpdater = ProximityRepository(SharedPrefsProximityStateStore(context)),
        statusUpdateSettingsStore = StatusUpdateSettingsStore(context),
        statusUpdateRepository = DefaultStatusUpdateRepository(),
    )

    private fun statusUpdateSwitch() =
        composeRule.onNodeWithTag(SettingsTestTags.STATUS_UPDATE_SWITCH)

    @Test
    fun toggleIsShownAndOnByDefault() {
        composeRule.setContent {
            SettingsScreen(viewModel = viewModel())
        }

        composeRule.onNodeWithText("Status updates").assertIsDisplayed()
        composeRule.onNodeWithText("Ask after clock-out").assertIsDisplayed()
        statusUpdateSwitch().assertIsOn()
    }

    @Test
    fun toggling_persistsThroughTheStore() {
        composeRule.setContent {
            SettingsScreen(viewModel = viewModel())
        }

        statusUpdateSwitch().performClick()
        statusUpdateSwitch().assertIsOff()

        val reread = StatusUpdateSettingsStore(context)
        assertFalse(reread.enabled.value)
    }
}
