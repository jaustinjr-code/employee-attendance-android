package com.jaustinjr.employeeattendance.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Modeled on [ClockNotificationSettingsStoreTest]. */
class StatusUpdateSettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore() = StatusUpdateSettingsStore(context)

    @Before
    @After
    fun clearPrefs() {
        SecurePreferences.create(context, "status_update_settings").edit().clear().commit()
        context.getSharedPreferences("status_update_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsToEnabled() {
        assertTrue(newStore().enabled.value)
    }

    @Test
    fun persistsSelectionAcrossInstances() {
        newStore().setEnabled(false)

        // A fresh instance (as after process death) reads the persisted choice.
        assertEquals(false, newStore().enabled.value)
    }

    @Test
    fun updatesTheObservableStateImmediately() {
        val store = newStore()
        store.setEnabled(false)
        assertEquals(false, store.enabled.value)
    }
}
