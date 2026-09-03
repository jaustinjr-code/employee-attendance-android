package com.jaustinjr.employeeattendance.location.tracking

import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationTrackingControllerTest {

    private class FakeLauncher(private val startSucceeds: Boolean = true) : TrackingServiceLauncher {
        var startCount = 0
        var stopCount = 0
        override fun start(): Boolean { startCount++; return startSucceeds }
        override fun stop() { stopCount++ }
    }

    private fun permission(level: LocationAccessLevel) =
        LocationPermissionState(level, isPrecise = true)

    @Test
    fun `always starts the tracking service`() {
        val launcher = FakeLauncher()
        val state = LocationStateRepository()
        val controller = LocationTrackingController(launcher, state)

        controller.sync(permission(LocationAccessLevel.ALWAYS))

        assertEquals(1, launcher.startCount)
        assertEquals(0, launcher.stopCount)
    }

    // Regression, issue #49: the platform refuses a foreground-service start whenever the process is
    // not foreground. That refusal must degrade tracking, not propagate and not leave the UI
    // claiming background tracking is running.
    @Test
    fun `a refused start degrades to foreground-only instead of throwing`() {
        val launcher = FakeLauncher(startSucceeds = false)
        val state = LocationStateRepository()
        val controller = LocationTrackingController(launcher, state)

        controller.sync(permission(LocationAccessLevel.ALWAYS))

        assertEquals(1, launcher.startCount)
        assertEquals(TrackingStatus.FOREGROUND_ONLY, state.trackingStatus.value)
    }

    @Test
    fun `an accepted start leaves the status for the service to report`() {
        val launcher = FakeLauncher(startSucceeds = true)
        val state = LocationStateRepository()
        val controller = LocationTrackingController(launcher, state)
        state.updateStatus(TrackingStatus.BACKGROUND_ACTIVE)

        controller.sync(permission(LocationAccessLevel.ALWAYS))

        // The service owns BACKGROUND_ACTIVE; sync must not stomp it with a degraded status.
        assertEquals(TrackingStatus.BACKGROUND_ACTIVE, state.trackingStatus.value)
    }

    @Test
    fun `when-in-use stops the service and reports foreground-only`() {
        val launcher = FakeLauncher()
        val state = LocationStateRepository()
        val controller = LocationTrackingController(launcher, state)

        controller.sync(permission(LocationAccessLevel.WHEN_IN_USE))

        assertEquals(1, launcher.stopCount)
        assertEquals(TrackingStatus.FOREGROUND_ONLY, state.trackingStatus.value)
    }

    @Test
    fun `none stops the service and reports stopped`() {
        val launcher = FakeLauncher()
        val state = LocationStateRepository()
        val controller = LocationTrackingController(launcher, state)

        controller.sync(permission(LocationAccessLevel.NONE))

        assertEquals(1, launcher.stopCount)
        assertEquals(TrackingStatus.STOPPED, state.trackingStatus.value)
    }

    @Test
    fun `stop stops the service and reports stopped`() {
        val launcher = FakeLauncher()
        val state = LocationStateRepository()
        val controller = LocationTrackingController(launcher, state)

        controller.stop()

        assertEquals(1, launcher.stopCount)
        assertEquals(TrackingStatus.STOPPED, state.trackingStatus.value)
    }
}
