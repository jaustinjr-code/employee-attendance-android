package com.jaustinjr.employeeattendance.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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
    fun encryptedValuesAreOnDiskBeforeThePlaintextFileIsCleared() {
        // Regression test for issue #24. Asserting via SharedPreferences would prove nothing: the
        // framework caches one in-memory instance per file per process, so a value written with the
        // old async apply() still reads back fine. Instead, inspect the backing XML file directly —
        // commit() writes it synchronously, apply() does not.
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putString("worksites", "[{\"id\":\"a\"}]").commit()

        SecurePreferences.create(context, name)

        val encryptedFile = File(context.dataDir, "shared_prefs/${name}_secure.xml")
        assertTrue("encrypted prefs file must exist on disk", encryptedFile.exists())
        assertTrue("encrypted prefs file must be non-empty", encryptedFile.length() > 0)
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun retryOfAnInterruptedMigrationDoesNotResurrectStalePlaintextValues() {
        // Migration committed the encrypted values but died before clearing the plaintext file, so
        // no completion marker was recorded. The retry must leave the newer encrypted value alone.
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putString("k", "stale").commit()

        val secure = SecurePreferences.create(context, name)
        secure.edit()
            .putString("k", "fresh")
            .remove(SecurePreferences.MIGRATION_COMPLETE_KEY)
            .commit()
        legacy.edit().putString("k", "stale").commit()

        assertEquals("fresh", SecurePreferences.create(context, name).getString("k", null))
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun plaintextFallbackWritesAreRecoveredOnTheNextHealthyLaunch() {
        // Migration completed (marker present), then a session fell back to the plaintext store
        // because encryption was unavailable and wrote there. Those values are newer and must win.
        SecurePreferences.create(context, name).edit().putString("k", "pre-fallback").commit()
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
            .edit().putString("k", "written-during-fallback").commit()

        assertEquals(
            "written-during-fallback",
            SecurePreferences.create(context, name).getString("k", null),
        )
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun missingKeyReturnsDefault() {
        assertNull(SecurePreferences.create(context, name).getString("absent", null))
    }
}
