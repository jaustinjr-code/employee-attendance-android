package com.jaustinjr.employeeattendance.devtools.ui

import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.devtools.DeveloperToolsController
import com.jaustinjr.employeeattendance.devtools.FakeDevAttendanceFacade
import com.jaustinjr.employeeattendance.devtools.FakeDevWorksiteFacade
import com.jaustinjr.employeeattendance.devtools.FakeDeveloperLogExporter
import com.jaustinjr.employeeattendance.devtools.FakeDeveloperSettingsStore
import com.jaustinjr.employeeattendance.devtools.FakePermissionRepository
import com.jaustinjr.employeeattendance.devtools.FakeProximityStateStore
import com.jaustinjr.employeeattendance.devtools.LogExportResult
import com.jaustinjr.employeeattendance.devtools.PermissionOverride
import com.jaustinjr.employeeattendance.devtools.RecordingDevNotificationPreview
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.TrackingStatus
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val office = WorkLocation(
        id = "office",
        name = "Downtown Office",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    private val settingsStore = FakeDeveloperSettingsStore()
    private val permissionRepository =
        FakePermissionRepository(LocationPermissionState(LocationAccessLevel.ALWAYS, true))
    private val proximityRepository = ProximityRepository(FakeProximityStateStore())
    private val locationState = LocationStateRepository()
    private val worksiteFacade = FakeDevWorksiteFacade()
    private val workLocations = worksiteFacade.repository
    private val attendanceFacade = FakeDevAttendanceFacade()
    private val attendance = attendanceFacade.repository
    private val logExporter = FakeDeveloperLogExporter()

    private val controller = DeveloperToolsController(
        settingsStore = settingsStore,
        permissionRepository = permissionRepository,
        proximityRepository = proximityRepository,
        locationStateRepository = locationState,
        worksites = worksiteFacade,
        attendance = attendanceFacade,
        notificationPreview = RecordingDevNotificationPreview(),
        logExporter = logExporter,
        buildDescription = "debug 1.0 (1)",
        clock = { 1_716_552_000_000L },
    )

    private fun viewModel() = DeveloperSettingsViewModel(
        controller = controller,
        settingsStore = settingsStore,
        permissionRepository = permissionRepository,
        locationStateRepository = locationState,
        proximityRepository = proximityRepository,
        workLocationRepository = workLocations,
        attendanceRepository = attendance,
    )

    @Test
    fun `ui state mirrors the live app state`() = runTest {
        workLocations.registerWorkLocation(office)
        locationState.updateStatus(TrackingStatus.BACKGROUND_ACTIVE)
        proximityRepository.onGeofenceTransition(office.id, ProximityState.INSIDE)
        attendance.recordClockIn(office.id)

        val viewModel = viewModel()
        val state = viewModel.uiState.first { it.activeWorksiteName != null }

        assertEquals(LocationAccessLevel.ALWAYS, state.accessLevel)
        assertTrue(state.isPrecise)
        assertEquals(TrackingStatus.BACKGROUND_ACTIVE, state.trackingStatus)
        assertEquals(ProximityState.INSIDE, state.proximity)
        assertEquals("Downtown Office", state.activeWorksiteName)
        assertTrue(state.isClockedIn)
        assertTrue(state.canSimulateWorksiteEvents)
        assertFalse(state.hasActiveOverrides)
    }

    @Test
    fun `worksite-dependent actions are gated until a worksite is active`() = runTest {
        val viewModel = viewModel()
        val backgroundScope = launch { viewModel.uiState.collect {} }

        assertFalse(viewModel.uiState.value.canSimulateWorksiteEvents)

        viewModel.onSeedSampleWorksite()

        assertTrue(viewModel.uiState.first { it.canSimulateWorksiteEvents }.canSimulateWorksiteEvents)
        backgroundScope.cancel()
    }

    @Test
    fun `an override is reflected as an active override in the ui state`() = runTest {
        val viewModel = viewModel()
        val backgroundScope = launch { viewModel.uiState.collect {} }

        viewModel.onPermissionOverrideSelected(PermissionOverride.DENIED)

        val state = viewModel.uiState.first { it.hasActiveOverrides }
        assertEquals(PermissionOverride.DENIED, state.permissionOverride)
        backgroundScope.cancel()
    }

    @Test
    fun `an action on a missing worksite reports why rather than appearing to succeed`() {
        val viewModel = viewModel()

        viewModel.onSimulateArrival()

        assertEquals(R.string.dev_action_needs_worksite, viewModel.message.value?.textRes)
    }

    @Test
    fun `a successful action reports done`() {
        workLocations.registerWorkLocation(office)
        val viewModel = viewModel()

        viewModel.onSimulateArrival()

        assertEquals(R.string.dev_action_done, viewModel.message.value?.textRes)
    }

    @Test
    fun `a message is cleared once shown`() {
        val viewModel = viewModel()
        viewModel.onClearProximity()

        viewModel.onMessageShown()

        assertNull(viewModel.message.value)
    }

    @Test
    fun `reset reports its own message rather than the generic done`() {
        val viewModel = viewModel()

        viewModel.onResetDeveloperConfiguration()

        assertEquals(R.string.dev_reset_done, viewModel.message.value?.textRes)
    }

    @Test
    fun `exporting the log reports the attached line count`() = runTest {
        logExporter.willReturn(LogExportResult.Launched("log.txt", 42))
        val viewModel = viewModel()

        viewModel.onExportLog()

        val message = viewModel.message.value!!
        assertEquals(R.string.dev_log_export_launched, message.textRes)
        assertEquals("42", message.detail)
        assertFalse(viewModel.uiState.value.isExportingLog)
    }

    @Test
    fun `an empty log is reported distinctly from a failure`() = runTest {
        logExporter.willReturn(LogExportResult.Empty)
        val viewModel = viewModel()

        viewModel.onExportLog()

        assertEquals(R.string.dev_log_export_empty, viewModel.message.value?.textRes)
    }

    @Test
    fun `an export failure surfaces the reason`() = runTest {
        logExporter.willReturn(LogExportResult.Failed("permission denied"))
        val viewModel = viewModel()

        viewModel.onExportLog()

        val message = viewModel.message.value!!
        assertEquals(R.string.dev_log_export_failed, message.textRes)
        assertEquals("permission denied", message.detail)
    }

    @Test
    fun `the log recipient is trimmed and persisted`() = runTest {
        val viewModel = viewModel()

        viewModel.onLogRecipientChanged("  dev@example.com ")

        assertEquals("dev@example.com", settingsStore.logRecipient.value)
    }

    @Test
    fun `posting a notification for a worksite is forwarded to the controller`() {
        workLocations.registerWorkLocation(office)
        val viewModel = viewModel()

        viewModel.onPostNotification(ClockType.CLOCK_IN, withUndo = true, confirm = false)

        assertEquals(R.string.dev_action_done, viewModel.message.value?.textRes)
    }

    @Test
    fun `clearing simulated data spares the user's own attendance`() = runTest {
        workLocations.registerWorkLocation(office)
        attendance.recordClockIn(office.id, 1_000L, ClockSource.MANUAL)
        val viewModel = viewModel()

        viewModel.onForceClockIn()
        viewModel.onClearSimulatedData()

        assertEquals(R.string.dev_action_done, viewModel.message.value?.textRes)
        assertEquals(listOf(ClockSource.MANUAL), attendance.events.map { it.source })
    }

    @Test
    fun `tracking status selection reaches the shared state`() {
        val viewModel = viewModel()

        viewModel.onTrackingStatusSelected(TrackingStatus.FOREGROUND_ONLY)

        assertEquals(TrackingStatus.FOREGROUND_ONLY, locationState.trackingStatus.value)
    }
}
