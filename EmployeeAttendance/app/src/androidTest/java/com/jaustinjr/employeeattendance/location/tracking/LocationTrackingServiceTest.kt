package com.jaustinjr.employeeattendance.location.tracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

class LocationTrackingServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val locationState =
        (context as EmployeeAttendanceApplication).container.locationStateRepository

    @Test
    fun stopWhenNotRunningDoesNotCrash() {
        // stop() is guarded against background-start IllegalStateException; when nothing is running
        // it is a safe no-op and must not throw.
        LocationTrackingService.stop(context)
    }

    @Test
    @Ignore(
        "Requires ACCESS_BACKGROUND_LOCATION and a foreground-service start context; verify on a " +
            "device or in an end-to-end run. Expected: start() -> BACKGROUND_ACTIVE + ongoing " +
            "notification; publishes fixes into locationState; stop() -> STOPPED.",
    )
    fun startThenStopTogglesTrackingStatus() {
        LocationTrackingService.start(context)
        // ... poll locationState.trackingStatus for BACKGROUND_ACTIVE ...
        LocationTrackingService.stop(context)
        assertEquals(TrackingStatus.STOPPED, locationState.trackingStatus.value)
    }
}
