package com.jaustinjr.employeeattendance.location.tracking

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPriorityTest {

    @Test
    fun `maps each level to its GMS priority`() {
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, LocationPriority.HIGH_ACCURACY.toGmsPriority())
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LocationPriority.BALANCED.toGmsPriority())
        assertEquals(Priority.PRIORITY_LOW_POWER, LocationPriority.LOW_POWER.toGmsPriority())
        assertEquals(Priority.PRIORITY_PASSIVE, LocationPriority.PASSIVE.toGmsPriority())
    }
}
