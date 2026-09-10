package com.jaustinjr.employeeattendance.devtools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Instrumented because `SharedPreferences` (and the encrypted store behind it) only behave for real
 * on a device — a JVM test would silently see stubbed defaults and prove nothing about persistence.
 */
class SharedPrefsDeveloperSettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun newStore() = SharedPrefsDeveloperSettingsStore(context)

    @Before
    @After
    fun clearPrefs() {
        // Both files: SecurePreferences stores under "<name>_secure", and migrates anything left in
        // the plaintext file back in on next use. Mirrors the other store tests in this source set.
        SecurePreferences.create(context, PREFS_NAME).edit().clear().commit()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToNoOverride() {
        val store = newStore()
        assertEquals(PermissionOverride.OFF, store.permissionOverride.value)
        assertEquals("", store.logRecipient.value)
    }

    @Test
    fun persistsThePermissionOverrideAcrossInstances() {
        newStore().setPermissionOverride(PermissionOverride.ALWAYS_APPROXIMATE)

        // A fresh instance stands in for process death: the override has to survive it, or a
        // geofence-driven cold start would silently revert to the real grant mid-test-session.
        assertEquals(PermissionOverride.ALWAYS_APPROXIMATE, newStore().permissionOverride.value)
    }

    @Test
    fun persistsAndTrimsTheLogRecipient() {
        newStore().setLogRecipient("  dev@example.com  ")

        assertEquals("dev@example.com", newStore().logRecipient.value)
    }

    @Test
    fun updatesTheObservableStateImmediately() {
        val store = newStore()

        store.setPermissionOverride(PermissionOverride.DENIED)

        assertEquals(PermissionOverride.DENIED, store.permissionOverride.value)
    }

    @Test
    fun resetClearsBothTheStateAndThePersistedValues() {
        val store = newStore()
        store.setPermissionOverride(PermissionOverride.ALWAYS_PRECISE)
        store.setLogRecipient("dev@example.com")

        store.reset()

        assertEquals(PermissionOverride.OFF, store.permissionOverride.value)
        assertEquals("", store.logRecipient.value)
        assertEquals(PermissionOverride.OFF, newStore().permissionOverride.value)
        assertEquals("", newStore().logRecipient.value)
    }

    private companion object {
        /** Mirrors the store's private `PREFS_NAME`. */
        const val PREFS_NAME = "developer_settings"
    }
}
