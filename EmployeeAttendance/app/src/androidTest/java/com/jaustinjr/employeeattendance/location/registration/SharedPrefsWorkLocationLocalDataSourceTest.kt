package com.jaustinjr.employeeattendance.location.registration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedPrefsWorkLocationLocalDataSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore() = SharedPrefsWorkLocationLocalDataSource(context)

    @Before
    @After
    fun clearPrefs() {
        SecurePreferences.create(context, "work_locations").edit().clear().commit()
        context.getSharedPreferences("work_locations", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsToEmptyWhenNothingStored() {
        val loaded = newStore().load()
        assertTrue(loaded.locations.isEmpty())
        assertNull(loaded.activeId)
    }

    @Test
    fun roundTripsAcrossInstances() {
        val office = WorkLocation("a", "Downtown Office", "123 Market St", 37.7749, -122.4194, 150f)
        val warehouse = WorkLocation("b", "Warehouse", null, 37.80, -122.27, 200f)
        newStore().save(listOf(office, warehouse), activeId = "b")

        // A fresh instance (as after process death) reads the persisted values back.
        val reloaded = newStore().load()
        assertEquals(listOf(office, warehouse), reloaded.locations)
        assertEquals("b", reloaded.activeId)
    }

    @Test
    fun dropsActiveIdThatNoLongerMapsToAStoredLocation() {
        val office = WorkLocation("a", "Downtown Office", null, 37.7749, -122.4194, 150f)
        newStore().save(listOf(office), activeId = "gone")

        assertNull(newStore().load().activeId)
    }

    @Test
    fun toleratesCorruptPayload() {
        context.getSharedPreferences("work_locations", Context.MODE_PRIVATE)
            .edit().putString("locations", "{not valid json").commit()

        assertTrue(newStore().load().locations.isEmpty())
    }
}
