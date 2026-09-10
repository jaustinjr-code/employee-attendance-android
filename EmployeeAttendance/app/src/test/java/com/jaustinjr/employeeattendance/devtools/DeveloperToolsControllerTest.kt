package com.jaustinjr.employeeattendance.devtools

import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.devtools.facade.DevNotificationPreview
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.location.proximity.ProximityEvent
import com.jaustinjr.employeeattendance.location.proximity.ProximityRepository
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.RadiusOption
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import com.jaustinjr.employeeattendance.location.tracking.LocationStateRepository
import com.jaustinjr.employeeattendance.location.tracking.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperToolsControllerTest {

    private val office = WorkLocation(
        id = "office",
        name = "Downtown Office",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    private val settingsStore = FakeDeveloperSettingsStore()
    private val permissionRepository =
        FakePermissionRepository(LocationPermissionState(LocationAccessLevel.WHEN_IN_USE, true))
    private val proximityRepository = ProximityRepository(FakeProximityStateStore())
    private val locationState = LocationStateRepository()
    private val worksiteFacade = FakeDevWorksiteFacade()
    private val workLocations = worksiteFacade.repository
    private val attendanceFacade = FakeDevAttendanceFacade()
    private val attendance = attendanceFacade.repository
    private val notificationPreview = RecordingDevNotificationPreview()
    private val notifier = notificationPreview.notifications
    private val logExporter = FakeDeveloperLogExporter()

    private val now = 1_716_552_000_000L

    private val controller = DeveloperToolsController(
        settingsStore = settingsStore,
        permissionRepository = permissionRepository,
        proximityRepository = proximityRepository,
        locationStateRepository = locationState,
        worksites = worksiteFacade,
        attendance = attendanceFacade,
        notificationPreview = notificationPreview,
        logExporter = logExporter,
        buildDescription = "debug 1.0 (1)",
        clock = { now },
    )

    private fun withActiveWorksite() {
        workLocations.registerWorkLocation(office)
        workLocations.setActiveWorkLocation(office.id)
    }

    // ------------------------------------------------------------------ permission

    @Test
    fun `setting a permission override persists it and refreshes the repository`() {
        val outcome = controller.setPermissionOverride(PermissionOverride.ALWAYS_PRECISE)

        assertEquals(DevActionOutcome.Done, outcome)
        assertEquals(PermissionOverride.ALWAYS_PRECISE, settingsStore.permissionOverride.value)
        // Without the refresh, clearing an override would leave the app on a stale real grant until
        // the next lifecycle resume.
        assertEquals(1, permissionRepository.refreshCount)
    }

    // ------------------------------------------------------------------ proximity

    @Test
    fun `simulating arrival drives the real proximity state and emits Arrived`() = runTest {
        withActiveWorksite()
        val events = async { proximityRepository.events.first() }
        runCurrent()

        assertEquals(DevActionOutcome.Done, controller.simulateArrival())

        assertEquals(ProximityState.INSIDE, proximityRepository.proximity.value)
        assertEquals(ProximityEvent.Arrived(office.id), events.await())
    }

    @Test
    fun `simulating departure after an arrival emits Departed`() = runTest {
        withActiveWorksite()
        controller.simulateArrival()
        val event = async { proximityRepository.events.first() }
        runCurrent()

        assertEquals(DevActionOutcome.Done, controller.simulateDeparture())

        assertEquals(ProximityState.OUTSIDE, proximityRepository.proximity.value)
        assertEquals(ProximityEvent.Departed(office.id), event.await())
    }

    @Test
    fun `simulations that need a worksite report so instead of doing nothing silently`() {
        assertEquals(DevActionOutcome.NoActiveWorksite, controller.simulateArrival())
        assertEquals(DevActionOutcome.NoActiveWorksite, controller.simulateDeparture())
        assertEquals(DevActionOutcome.NoActiveWorksite, controller.simulateLocationAtWorksite())
        assertEquals(DevActionOutcome.NoActiveWorksite, controller.simulateLocationAwayFromWorksite())
        assertEquals(
            DevActionOutcome.NoActiveWorksite,
            controller.postClockNotification(ClockType.CLOCK_IN, withUndo = true, confirm = false),
        )
        assertEquals(ProximityState.UNKNOWN, proximityRepository.proximity.value)
    }

    @Test
    fun `clearing proximity returns it to UNKNOWN`() {
        withActiveWorksite()
        controller.simulateArrival()

        assertEquals(DevActionOutcome.Done, controller.clearProximity())

        assertEquals(ProximityState.UNKNOWN, proximityRepository.proximity.value)
    }

    // ------------------------------------------------------------------ simulated fixes

    @Test
    fun `a fix at the worksite is published at its exact centre`() {
        withActiveWorksite()

        assertEquals(DevActionOutcome.Done, controller.simulateLocationAtWorksite())

        val fix = locationState.latestLocation.value
        assertNotNull(fix)
        assertEquals(office.latitudeDegrees, fix!!.latitudeDegrees, 0.0)
        assertEquals(office.longitudeDegrees, fix.longitudeDegrees, 0.0)
        assertEquals(now, fix.timestampEpochMillis)
    }

    @Test
    fun `a fix away from the worksite clears the radius and the exit hysteresis buffer`() {
        withActiveWorksite()

        controller.simulateLocationAwayFromWorksite()

        val fix = locationState.latestLocation.value!!
        val metersMoved = abs(fix.latitudeDegrees - office.latitudeDegrees) * METERS_PER_DEGREE_LAT
        // ProximityRepository's default exit buffer is 50 m on top of the radius; anything less and
        // the foreground evaluator would keep reporting INSIDE.
        assertTrue(
            "moved ${metersMoved}m, needs > ${office.radiusMeters + 50f}",
            metersMoved > office.radiusMeters + 50f,
        )
        assertEquals(office.longitudeDegrees, fix.longitudeDegrees, 0.0)
    }

    @Test
    fun `tracking status is forced onto the shared state`() {
        assertEquals(
            DevActionOutcome.Done,
            controller.setTrackingStatus(TrackingStatus.BACKGROUND_ACTIVE),
        )
        assertEquals(TrackingStatus.BACKGROUND_ACTIVE, locationState.trackingStatus.value)
    }

    // ------------------------------------------------------------------ worksites

    @Test
    fun `seeding a worksite registers it, activates it, and uses the default radius`() {
        assertEquals(DevActionOutcome.Done, controller.seedSampleWorksite())

        val active = workLocations.activeWorkLocation.value
        assertNotNull(active)
        assertEquals("Dev Sample Worksite", active!!.name)
        assertEquals(RadiusOption.DEFAULT.meters, active.radiusMeters)
        assertEquals(1, workLocations.workLocations.value.size)
    }

    @Test
    fun `seeding twice replaces the sample rather than accumulating duplicates`() {
        controller.seedSampleWorksite()
        controller.seedSampleWorksite()

        assertEquals(1, workLocations.workLocations.value.size)
    }

    @Test
    fun `a seeded worksite is centred on the latest fix when one exists`() {
        locationState.publishLocation(
            LocationSample(
                latitudeDegrees = 51.5,
                longitudeDegrees = -0.12,
                accuracyMeters = 8f,
                timestampEpochMillis = now,
            )
        )

        controller.seedSampleWorksite()

        val active = workLocations.activeWorkLocation.value!!
        assertEquals(51.5, active.latitudeDegrees, 0.0)
        assertEquals(-0.12, active.longitudeDegrees, 0.0)
    }

    @Test
    fun `clearing worksites empties the repository`() {
        withActiveWorksite()

        assertEquals(DevActionOutcome.Done, controller.clearWorksites())

        assertTrue(workLocations.workLocations.value.isEmpty())
        assertNull(workLocations.activeWorkLocation.value)
    }

    // ------------------------------------------------------------------ attendance

    @Test
    fun `forced clock events are recorded against the active worksite as simulated`() {
        withActiveWorksite()

        controller.forceClockIn()
        controller.forceClockOut()

        assertEquals(2, attendance.events.size)
        attendance.events.forEach {
            assertEquals(office.id, it.locationId)
            // Provenance: a developer's direct write is spelled differently in the data than a real
            // one, which is what makes `clearSimulatedData` possible at all.
            assertEquals(ClockSource.SIMULATED, it.source)
            assertEquals(now, it.epochMillis)
        }
        assertEquals(ClockType.CLOCK_IN, attendance.events[0].type)
        assertEquals(ClockType.CLOCK_OUT, attendance.events[1].type)
    }

    @Test
    fun `with no worksite, forced clock events fall back to the general timeclock`() {
        controller.forceClockIn()

        assertEquals(
            AttendanceRepository.GENERAL_TIMECLOCK_ID,
            attendance.events.single().locationId,
        )
    }

    @Test
    fun `clearing attendance empties the log`() {
        withActiveWorksite()
        controller.forceClockIn()

        assertEquals(DevActionOutcome.Done, controller.clearAttendance())

        assertTrue(attendance.events.isEmpty())
    }

    @Test
    fun `a simulated clock-out does not surface as a manual one`() {
        withActiveWorksite()

        controller.forceClockOut()

        // The only production branch on ClockSource is `lastClockOutManual`; SIMULATED must fall in
        // the same bucket as AUTO or the attendance screen would start showing developer events.
        assertFalse(attendance.attendance.value.getValue(office.id).lastClockOutManual)
    }

    @Test
    fun `an event recorded by the real pipeline stays AUTO and is not treated as simulated`() {
        withActiveWorksite()
        // Stands in for AttendanceAutoClockController writing during simulateArrival().
        attendance.recordClockIn(office.id, now, ClockSource.AUTO)

        controller.clearSimulatedData()

        assertEquals(1, attendance.events.size)
        assertEquals(ClockSource.AUTO, attendance.events.single().source)
    }

    // ------------------------------------------------------------------ clearing simulated data

    @Test
    fun `clearing simulated data removes developer events and the sample worksite only`() {
        workLocations.registerWorkLocation(office)
        controller.seedSampleWorksite()
        controller.forceClockIn()
        attendance.recordClockIn("office", 1_000L, ClockSource.MANUAL)
        attendance.recordClockOut("office", 2_000L, ClockSource.AUTO)

        assertEquals(DevActionOutcome.Done, controller.clearSimulatedData())

        assertEquals(
            listOf(ClockSource.MANUAL, ClockSource.AUTO),
            attendance.events.map { it.source },
        )
        assertEquals(listOf(office.id), workLocations.workLocations.value.map { it.id })
    }

    @Test
    fun `clearing simulated data leaves an untouched app alone`() {
        withActiveWorksite()

        assertEquals(DevActionOutcome.Done, controller.clearSimulatedData())

        assertEquals(1, workLocations.workLocations.value.size)
        assertTrue(attendance.events.isEmpty())
    }

    // ------------------------------------------------------------------ notifications

    @Test
    fun `posting notifications selects the recorded or confirm variant and records nothing`() {
        withActiveWorksite()

        controller.postClockNotification(ClockType.CLOCK_IN, withUndo = true, confirm = false)
        controller.postClockNotification(ClockType.CLOCK_OUT, withUndo = false, confirm = true)

        val posted = notifier.recorded.single()
        assertEquals(DevNotificationPreview.DEV_PREVIEW_WORKSITE_ID, posted.worksite.id)
        assertEquals(ClockType.CLOCK_IN, posted.event.type)
        assertTrue(posted.withUndo)
        val confirmed = notifier.confirms.single()
        assertEquals(DevNotificationPreview.DEV_PREVIEW_WORKSITE_ID, confirmed.worksite.id)
        assertEquals(ClockType.CLOCK_OUT, confirmed.clockType)
        // Posting a card is a UI check, not an attendance event.
        assertTrue(attendance.events.isEmpty())
    }


    @Test
    fun `a preview keeps the real worksite name so the genuine card is what renders`() {
        withActiveWorksite()

        controller.postClockNotification(ClockType.CLOCK_IN, withUndo = true, confirm = false)

        assertEquals(office.name, notifier.recorded.single().worksite.name)
    }

    @Test
    fun `the undo on a posted preview names no real event, so it cannot delete one`() {
        withActiveWorksite()
        // A genuine record the developer would not want a UI preview to destroy.
        attendance.recordClockIn(office.id, now - 60_000L, ClockSource.AUTO)

        controller.postClockNotification(ClockType.CLOCK_IN, withUndo = true, confirm = false)
        val previewEvent = notifier.recorded.single().event

        // Replays what ClockActionReceiver does when Undo is tapped on that card.
        val undone = attendance.undoEvent(
            previewEvent.locationId,
            previewEvent.type,
            previewEvent.epochMillis,
        )

        assertFalse("a preview's undo must not name a real event", undone)
        assertEquals(1, attendance.events.size)
    }

    // ------------------------------------------------------------------ log export

    @Test
    fun `exporting the log passes the configured recipient and a state header`() = runTest {
        withActiveWorksite()
        settingsStore.setLogRecipient("  dev@example.com  ")

        val result = controller.exportLog()

        assertTrue(result is LogExportResult.Launched)
        assertEquals("dev@example.com", logExporter.lastRecipient)
        val header = logExporter.lastHeader!!
        assertTrue(header, header.contains("debug 1.0 (1)"))
        assertTrue(header, header.contains("WHEN_IN_USE"))
        assertTrue(header, header.contains(office.id))
    }

    @Test
    fun `the state header reports the override, not only the real grant`() {
        controller.setPermissionOverride(PermissionOverride.DENIED)

        val header = controller.describeCurrentState()

        assertTrue(header, header.contains("permission override: DENIED"))
    }

    // ------------------------------------------------------------------ reset

    @Test
    fun `reset clears overrides and the simulated state they produced`() {
        withActiveWorksite()
        controller.setPermissionOverride(PermissionOverride.ALWAYS_PRECISE)
        settingsStore.setLogRecipient("dev@example.com")
        controller.simulateArrival()
        controller.setTrackingStatus(TrackingStatus.BACKGROUND_ACTIVE)

        assertEquals(DevActionOutcome.Done, controller.resetDeveloperConfiguration())

        assertEquals(1, settingsStore.resetCount)
        assertEquals(PermissionOverride.OFF, settingsStore.permissionOverride.value)
        assertEquals("", settingsStore.logRecipient.value)
        assertEquals(ProximityState.UNKNOWN, proximityRepository.proximity.value)
        assertEquals(TrackingStatus.STOPPED, locationState.trackingStatus.value)
    }

    @Test
    fun `reset leaves user data alone`() {
        withActiveWorksite()
        controller.forceClockIn()

        controller.resetDeveloperConfiguration()

        // Worksites and attendance history are the user's, not developer configuration; they have
        // their own explicit actions.
        assertEquals(1, workLocations.workLocations.value.size)
        assertEquals(1, attendance.events.size)
    }

    @Test
    fun `reset re-reads the real permission grant`() {
        controller.setPermissionOverride(PermissionOverride.DENIED)
        val before = permissionRepository.refreshCount

        controller.resetDeveloperConfiguration()

        assertFalse(permissionRepository.refreshCount == before)
    }

    private companion object {
        const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}
