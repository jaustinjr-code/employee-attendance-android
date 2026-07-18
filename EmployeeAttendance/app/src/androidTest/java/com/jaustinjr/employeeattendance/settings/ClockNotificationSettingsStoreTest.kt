package com.jaustinjr.employeeattendance.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ClockNotificationSettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore() = ClockNotificationSettingsStore(context)

    @Before
    @After
    fun clearPrefs() {
        context.getSharedPreferences("clock_notification_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsToTheRecommendedPreference() {
        assertEquals(ClockNotificationPreference.DEFAULT, newStore().preference.value)
    }

    @Test
    fun persistsSelectionAcrossInstances() {
        newStore().setPreference(ClockNotificationPreference.CONFIRM)

        // A fresh instance (as after process death) reads the persisted choice.
        assertEquals(ClockNotificationPreference.CONFIRM, newStore().preference.value)
    }

    @Test
    fun updatesTheObservableStateImmediately() {
        val store = newStore()
        store.setPreference(ClockNotificationPreference.SILENT)
        assertEquals(ClockNotificationPreference.SILENT, store.preference.value)
    }
}
