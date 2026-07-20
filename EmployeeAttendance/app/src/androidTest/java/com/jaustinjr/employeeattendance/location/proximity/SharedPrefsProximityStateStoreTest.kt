package com.jaustinjr.employeeattendance.location.proximity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SharedPrefsProximityStateStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore() = SharedPrefsProximityStateStore(context)

    @Before
    @After
    fun clearPrefs() {
        // Data now lives in the encrypted store; clear both it and any legacy plaintext file.
        SecurePreferences.create(context, "proximity_state").edit().clear().commit()
        context.getSharedPreferences("proximity_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsToUnknownWhenEmpty() {
        val store = newStore()
        assertEquals(ProximityState.UNKNOWN, store.load())
        assertNull(store.loadTargetId())
    }

    @Test
    fun roundTripsAcrossInstances() {
        newStore().save(ProximityState.INSIDE, "office")

        // A fresh instance (as after process death) reads the persisted values.
        val reloaded = newStore()
        assertEquals(ProximityState.INSIDE, reloaded.load())
        assertEquals("office", reloaded.loadTargetId())
    }

    @Test
    fun toleratesUnknownPersistedEnumName() {
        context.getSharedPreferences("proximity_state", Context.MODE_PRIVATE)
            .edit().putString("state", "NOT_A_STATE").commit()

        assertEquals(ProximityState.UNKNOWN, newStore().load())
    }
}
