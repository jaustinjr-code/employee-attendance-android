package com.jaustinjr.employeeattendance.location.tracking

import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationTrackingControllerTest {

    private class FakeLauncher : TrackingServiceLauncher {
        var startCount = 0
        var stopCount = 0
        override fun start() { startCount++ }
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
