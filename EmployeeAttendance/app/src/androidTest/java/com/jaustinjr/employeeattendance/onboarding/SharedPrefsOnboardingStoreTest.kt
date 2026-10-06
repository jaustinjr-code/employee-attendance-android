package com.jaustinjr.employeeattendance.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Modeled on `StatusUpdateSettingsStoreTest`. */
class SharedPrefsOnboardingStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore() = SharedPrefsOnboardingStore(context)

    @Before
    @After
    fun clearPrefs() {
        SecurePreferences.create(context, "onboarding").edit().clear().commit()
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToNotCompleted() {
        assertFalse(newStore().completed.value)
    }

    @Test
    fun updatesTheObservableStateImmediately() {
        val store = newStore()
        store.markCompleted()
        assertTrue(store.completed.value)
    }

    @Test
    fun persistsCompletionAcrossInstances() {
        newStore().markCompleted()

        // A fresh instance (as after process death) must not show onboarding again.
        assertTrue(newStore().completed.value)
    }
}
