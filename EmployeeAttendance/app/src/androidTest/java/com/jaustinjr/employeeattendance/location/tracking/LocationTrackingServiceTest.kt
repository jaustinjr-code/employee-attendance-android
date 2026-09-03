package com.jaustinjr.employeeattendance.location.tracking

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * Regression cover for issue #49.
     *
     * The crash was `ContextCompat.startForegroundService()` throwing
     * `ForegroundServiceStartNotAllowedException` on an unhandled `Dispatchers.Default` worker.
     * These drive that refusal deterministically through a [ContextWrapper], rather than trying to
     * coax the real `ActivityManager` into refusing — which depends on process state and would both
     * flake and leave an undischarged `startForegroundService()` obligation behind (see TODO(#50)
     * in the service).
     *
     * This belongs in `androidTest` rather than the JVM suite: `isReturnDefaultValues` makes
     * `ContextCompat`'s API-level branch and the `Intent` construction meaningless on the JVM.
     */
    private fun contextThatFailsStartWith(error: RuntimeException) = object : ContextWrapper(context) {
        override fun startForegroundService(service: Intent): ComponentName? = throw error
        override fun startService(service: Intent): ComponentName? = throw error
    }

    @Test
    fun startReportsRefusalInsteadOfThrowing() {
        // The exact shape of the crash: ForegroundServiceStartNotAllowedException is an
        // IllegalStateException, which is why the service catches the supertype at minSdk 24.
        val refusing = contextThatFailsStartWith(
            IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false"),
        )

        assertFalse(LocationTrackingService.start(refusing))
    }

    @Test
    fun startReportsDenialInsteadOfThrowing() {
        val denying = contextThatFailsStartWith(SecurityException("permission revoked"))

        assertFalse(LocationTrackingService.start(denying))
    }

    @Test
    fun startReportsAcceptance() {
        // Accepts the intent and does nothing else, so no real service (and no foreground-service
        // obligation) is created by this test.
        val accepting = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? = null
            override fun startService(service: Intent): ComponentName? = null
        }

        assertTrue(LocationTrackingService.start(accepting))
    }

    @Test
    fun stopStillSwallowsARefusedStop() {
        val refusing = contextThatFailsStartWith(IllegalStateException("background start not allowed"))

        LocationTrackingService.stop(refusing)
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
