package com.jaustinjr.employeeattendance.location.geofence

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the receiver's guard paths (no proximity change, no crash) for malformed broadcasts. Real
 * ENTER/EXIT delivery is driven by Play Services and is exercised manually / in e2e.
 */
class GeofenceBroadcastReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val proximity =
        (context as EmployeeAttendanceApplication).container.proximityRepository

    @Test
    fun ignoresIntentWithWrongAction() {
        val before = proximity.proximity.value
        val receiver = GeofenceBroadcastReceiver()

        receiver.onReceive(context, Intent("com.example.SOMETHING_ELSE"))

        assertEquals(before, proximity.proximity.value)
    }

    @Test
    fun ignoresGeofenceIntentWithoutEventExtras() {
        val before = proximity.proximity.value
        val receiver = GeofenceBroadcastReceiver()

        // Correct action but no GeofencingEvent payload -> fromIntent is null/errored -> ignored.
        receiver.onReceive(
            context,
            Intent(GeofenceBroadcastReceiver.ACTION_GEOFENCE_EVENT),
        )

        assertEquals(before, proximity.proximity.value)
    }
}
