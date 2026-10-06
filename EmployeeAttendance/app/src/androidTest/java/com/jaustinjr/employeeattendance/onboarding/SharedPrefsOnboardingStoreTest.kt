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

    @Test
    fun resetForNextLaunchShowsOnboardingOnTheNextLaunchOnly() {
        val running = newStore()
        running.markCompleted()

        running.resetForNextLaunch()

        // The running app keeps its state, so it isn't swapped into the carousel mid-session...
        assertTrue(running.completed.value)
        assertTrue(running.resetPending.value)
        // ...while a fresh instance, as after a restart, shows onboarding again.
        val nextLaunch = newStore()
        assertFalse(nextLaunch.completed.value)
        assertFalse(nextLaunch.resetPending.value)
    }

    @Test
    fun completingAgainClearsAPendingReset() {
        val store = newStore()
        store.resetForNextLaunch()

        store.markCompleted()

        assertFalse(store.resetPending.value)
        assertTrue(newStore().completed.value)
    }
}
