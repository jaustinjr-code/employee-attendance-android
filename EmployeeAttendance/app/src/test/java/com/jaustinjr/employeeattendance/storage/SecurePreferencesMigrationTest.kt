package com.jaustinjr.employeeattendance.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Durability tests for the legacy-plaintext -> encrypted migration (issue #24).
 *
 * [FakeSharedPreferences] models `apply()` as "enqueued but not durable" and `commit()` as
 * "written through", so a migration that clears the plaintext file after a mere `apply()` shows up
 * here as data that exists in neither store — exactly the loss a mid-migration process kill causes
 * on a real device.
 */
class SecurePreferencesMigrationTest {

    private val log = mutableListOf<String>()

    private fun plaintext(initial: Map<String, Any?>) =
        FakeSharedPreferences("plaintext", log, initial)

    private fun encrypted(initial: Map<String, Any?> = emptyMap()) =
        FakeSharedPreferences("encrypted", log, initial)

    @Test
    fun `migration makes encrypted write durable before clearing plaintext`() {
        val plaintext = plaintext(mapOf("worksites" to "[{\"id\":\"a\"}]"))
        val encrypted = encrypted()

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        // The encrypted store must be written with a synchronous commit, and that commit must land
        // strictly before the plaintext file is cleared. Any apply() on the encrypted store means
        // the clear could win the race and destroy the only copy.
        assertEquals(listOf("encrypted.commit", "plaintext.commit"), log)
        assertFalse("migration must not use apply()", log.any { it.endsWith(".apply") })
        assertEquals("[{\"id\":\"a\"}]", encrypted.durable["worksites"])
        assertTrue(plaintext.durable.isEmpty())
    }

    @Test
    fun `data survives a process kill immediately after the migration writes`() {
        val plaintext = plaintext(mapOf("worksites" to "site-json", "count" to 3))
        val encrypted = encrypted()

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        // `durable` is what would survive `am kill`: the value must be readable from at least one
        // of the two stores at every point, and here specifically from the encrypted one.
        assertEquals("site-json", encrypted.durable["worksites"])
        assertEquals(3, encrypted.durable["count"])
    }

    @Test
    fun `plaintext is preserved when the encrypted commit fails`() {
        val plaintext = plaintext(mapOf("worksites" to "site-json"))
        val encrypted = encrypted()
        encrypted.commitSucceeds = false

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        assertTrue("encrypted store must stay empty", encrypted.durable.isEmpty())
        assertEquals(
            "plaintext must be kept so the migration can be retried",
            "site-json",
            plaintext.durable["worksites"],
        )
        assertFalse("plaintext must not be cleared", log.contains("plaintext.commit"))
    }

    @Test
    fun `retry after a failed plaintext clear does not overwrite newer encrypted values`() {
        // First launch: encrypted commit succeeds but the plaintext clear fails, so the stale
        // plaintext copy is still on disk.
        val plaintext = plaintext(mapOf("worksites" to "stale"))
        val encrypted = encrypted()
        plaintext.commitSucceeds = false

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)
        assertEquals("stale", encrypted.durable["worksites"])
        assertEquals("stale", plaintext.durable["worksites"])

        // The app then writes a newer value to the encrypted store.
        encrypted.edit().putString("worksites", "fresh").commit()

        // Second launch re-runs the migration; the leftover stale plaintext must not clobber it.
        plaintext.commitSucceeds = true
        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        assertEquals("fresh", encrypted.durable["worksites"])
        assertTrue("plaintext should finally be cleared", plaintext.durable.isEmpty())
    }

    @Test
    fun `all supported value types are migrated`() {
        val plaintext = plaintext(
            mapOf(
                "s" to "text",
                "b" to true,
                "i" to 7,
                "l" to 42L,
                "f" to 1.5f,
                "set" to setOf("x", "y"),
            ),
        )
        val encrypted = encrypted()

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        assertEquals("text", encrypted.durable["s"])
        assertEquals(true, encrypted.durable["b"])
        assertEquals(7, encrypted.durable["i"])
        assertEquals(42L, encrypted.durable["l"])
        assertEquals(1.5f, encrypted.durable["f"])
        assertEquals(setOf("x", "y"), encrypted.durable["set"])
        assertTrue(plaintext.durable.isEmpty())
    }

    @Test
    fun `an empty plaintext store performs no writes at all`() {
        val plaintext = plaintext(emptyMap())
        val encrypted = encrypted(mapOf("existing" to "value"))

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        assertTrue("no store should be touched", log.isEmpty())
        assertEquals("value", encrypted.durable["existing"])
    }

    @Test
    fun `unmigratable entries are skipped without blocking the rest`() {
        val plaintext = plaintext(
            mapOf(
                "good" to "keep-me",
                "badSet" to setOf("ok", 5),
            ),
        )
        val encrypted = encrypted()

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        assertEquals("keep-me", encrypted.durable["good"])
        assertFalse(encrypted.durable.containsKey("badSet"))
    }
}
