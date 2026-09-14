package com.jaustinjr.employeeattendance.statusupdate

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the "registered too late" bug: [DefaultAppForegroundTracker] must be built and
 * registered in [EmployeeAttendanceApplication.onCreate], before any Activity's first `onStart`, or
 * its activity count only ever sees the matching `onStop` and [AppForegroundTracker.isForeground]
 * never recovers to `true`.
 */
class AppForegroundTrackerTest {

    private val app = ApplicationProvider.getApplicationContext<EmployeeAttendanceApplication>()

    @Test
    fun isForegroundWhileAnActivityIsResumedAndFalseOnceItStops() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(app.appForegroundTracker.isForeground.value)
        } finally {
            scenario.close()
        }

        assertFalse(app.appForegroundTracker.isForeground.value)
    }
}
