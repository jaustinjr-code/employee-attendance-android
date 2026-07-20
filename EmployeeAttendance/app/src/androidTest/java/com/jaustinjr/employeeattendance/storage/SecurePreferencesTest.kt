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
    fun missingKeyReturnsDefault() {
        assertNull(SecurePreferences.create(context, name).getString("absent", null))
    }
}
