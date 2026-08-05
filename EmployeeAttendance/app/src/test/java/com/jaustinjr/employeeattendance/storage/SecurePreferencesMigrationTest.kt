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
        // the clear could win the race and destroy the only copy. The trailing commit is the
        // completion marker, which must come last of all.
        assertEquals(listOf("encrypted.commit", "plaintext.commit", "encrypted.commit"), log)
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
    fun `an empty plaintext store only records the completion marker`() {
        val plaintext = plaintext(emptyMap())
        val encrypted = encrypted(mapOf("existing" to "value"))

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        assertFalse("the plaintext file must not be touched", log.contains("plaintext.commit"))
        assertEquals("value", encrypted.durable["existing"])
        assertEquals(true, encrypted.durable[SecurePreferences.MIGRATION_COMPLETE_KEY])
    }

    @Test
    fun `a second run with the marker already set writes nothing`() {
        val plaintext = plaintext(emptyMap())
        val encrypted = encrypted(mapOf(SecurePreferences.MIGRATION_COMPLETE_KEY to true))

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        assertTrue("no store should be touched", log.isEmpty())
    }

    @Test
    fun `values written during a plaintext-fallback session are recovered, not destroyed`() {
        // A completed migration, then a session where create() fell back to the plaintext store
        // because the keystore was unavailable: the user re-registered a worksite and it landed in
        // the plaintext file. Those values are NEWER than the encrypted copies and must win.
        val plaintext = plaintext(mapOf("worksites" to "re-registered"))
        val encrypted = encrypted(
            mapOf(
                "worksites" to "pre-fallback",
                SecurePreferences.MIGRATION_COMPLETE_KEY to true,
            ),
        )

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        assertEquals("re-registered", encrypted.durable["worksites"])
        assertTrue("plaintext must be cleared once recovered", plaintext.durable.isEmpty())
    }

    @Test
    fun `fallback recovery replaces the encrypted contents instead of merging them`() {
        // The fallback session saw an empty store, so its plaintext file is the complete view.
        // A key it deliberately cleared (here `target_id`) must not be resurrected from the
        // pre-fallback encrypted contents.
        val plaintext = plaintext(mapOf("state" to "OUTSIDE"))
        val encrypted = encrypted(
            mapOf(
                "state" to "INSIDE",
                "target_id" to "site-1",
                SecurePreferences.MIGRATION_COMPLETE_KEY to true,
            ),
        )

        SecurePreferences.migratePlaintext("proximity_state", plaintext, encrypted)

        assertEquals("OUTSIDE", encrypted.durable["state"])
        assertFalse(
            "a key cleared during the fallback session must stay cleared",
            encrypted.durable.containsKey("target_id"),
        )
        // The marker must survive the replace, or the next run would misread the store as
        // pre-migration and start skipping keys again.
        assertEquals(true, encrypted.durable[SecurePreferences.MIGRATION_COMPLETE_KEY])
    }

    @Test
    fun `a forged completion marker in the plaintext file is never migrated`() {
        val plaintext = plaintext(
            mapOf(SecurePreferences.MIGRATION_COMPLETE_KEY to true, "worksites" to "site"),
        )
        val encrypted = encrypted()

        SecurePreferences.migratePlaintext("work_locations", plaintext, encrypted)

        // The marker must come from step 3 of this migration, not from the untrusted plaintext file
        // — otherwise a tampered file could make the next run treat stale plaintext as newer.
        assertEquals("site", encrypted.durable["worksites"])
        assertEquals(listOf("encrypted.commit", "plaintext.commit", "encrypted.commit"), log)
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
