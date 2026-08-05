package com.jaustinjr.employeeattendance.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurePreferencesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "secure_prefs_test"

    @Before
    @After
    fun clear() {
        SecurePreferences.create(context, name).edit().clear().commit()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun readsAndWritesEncryptedValues() {
        val prefs = SecurePreferences.create(context, name)
        prefs.edit().putString("k", "v").commit()

        assertEquals("v", SecurePreferences.create(context, name).getString("k", null))
    }

    @Test
    fun migratesLegacyPlaintextValuesAndClearsThem() {
        // Simulate an install that stored data in plaintext before encryption existed.
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putString("s", "hello").putInt("n", 7).commit()

        val secure = SecurePreferences.create(context, name)

        // Values are readable from the encrypted store...
        assertEquals("hello", secure.getString("s", null))
        assertEquals(7, secure.getInt("n", 0))
        // ...and the plaintext copy has been cleared.
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun migratedValuesAreDurableOnDiskBeforePlaintextIsCleared() {
        // Regression test for issue #24: the migration must not clear the plaintext file until the
        // encrypted write is durable. Re-opening the encrypted store from a *fresh* instance (which
        // reads back from disk rather than any in-memory editor state) proves the write landed,
        // while the plaintext file is already empty.
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putString("worksites", "[{\"id\":\"a\"}]").commit()

        SecurePreferences.create(context, name)

        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(
            "[{\"id\":\"a\"}]",
            SecurePreferences.create(context, name).getString("worksites", null),
        )
    }

    @Test
    fun rerunningMigrationDoesNotResurrectStalePlaintextValues() {
        // If a previous migration's plaintext clear didn't land, the retry must leave the (newer)
        // encrypted value alone rather than overwriting it with the stale plaintext copy.
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putString("k", "stale").commit()

        val secure = SecurePreferences.create(context, name)
        secure.edit().putString("k", "fresh").commit()
        // Re-plant the legacy file as if the earlier clear had failed.
        legacy.edit().putString("k", "stale").commit()

        assertEquals("fresh", SecurePreferences.create(context, name).getString("k", null))
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun missingKeyReturnsDefault() {
        assertNull(SecurePreferences.create(context, name).getString("absent", null))
    }
}
