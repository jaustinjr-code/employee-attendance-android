package com.jaustinjr.employeeattendance.location.proximity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Device-level regression for the "Departed swallowed after process death" bug: a real
 * SharedPreferences-backed store lets a freshly-constructed repository restore the INSIDE state, so
 * a subsequent EXIT still emits Departed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProximityPersistenceE2ETest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPrefs() {
        context.getSharedPreferences("proximity_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun exitAfterProcessDeathEmitsDeparted() = runTest {
        val store = SharedPrefsProximityStateStore(context)

        // First "process": enter the geofence, persisting INSIDE.
        ProximityRepository(store).onGeofenceTransition("office", ProximityState.INSIDE)

        // Second "process": a new repository restores INSIDE from the store.
        val revived = ProximityRepository(store)
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { revived.events.collect(events::add) }
        runCurrent() // ensure the collector is subscribed before we emit

        revived.onGeofenceTransition("office", ProximityState.OUTSIDE)
        runCurrent()

        assertTrue(events.contains(ProximityEvent.Departed("office")))
    }
}
