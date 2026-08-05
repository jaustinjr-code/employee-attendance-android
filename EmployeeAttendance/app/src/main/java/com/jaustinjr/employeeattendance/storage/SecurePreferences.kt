package com.jaustinjr.employeeattendance.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory for encrypted [SharedPreferences] backing the app's sensitive on-device data (worksite
 * coordinates, attendance history, proximity state). Values are encrypted at rest with a hardware-
 * backed master key via Jetpack Security's [EncryptedSharedPreferences], so a rooted device or a
 * backup extraction can't read them in the clear.
 *
 * The encrypted store lives under a distinct file name ("<name>_secure"). On first use, any values
 * from a pre-existing plaintext prefs file of the original [name] are migrated in and the plaintext
 * file is cleared, so upgrades don't lose registered worksites or attendance.
 */
object SecurePreferences {

    private const val TAG = "SecurePrefs"

    /**
     * Marker written into the encrypted store once a migration has fully completed (values
     * committed *and* the plaintext file cleared). Reserved: it is never migrated from plaintext,
     * so a tampered plaintext file can't forge it. See [migratePlaintext] for why it exists.
     */
    internal const val MIGRATION_COMPLETE_KEY = "__secure_prefs_migration_complete__"

    /**
     * Per-store lock. `getSharedPreferences` hands every caller the same per-file instance, so two
     * concurrent [create] calls for one name would otherwise run the migration against the same
     * objects — and a plaintext write landing between reading `plaintext.all` and clearing the file
     * would be dropped.
     */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Returns the encrypted prefs for [name], migrating any legacy plaintext values first.
     *
     * If the encrypted store cannot be constructed at all (e.g. a corrupt keystore) this falls back
     * to plaintext prefs so the app still runs rather than crashing — logged so the degradation is
     * visible. A failure *inside the migration* never costs encryption: the encrypted store is
     * returned regardless and the migration is retried on the next launch.
     */
    fun create(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext
        val encrypted = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "${name}_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Degraded path: the keystore or the encrypted store is unusable.
            //
            // This is NOT data-safe in general. It only preserves data on the pre-migration path,
            // where the legacy plaintext file still holds the user's values. Once a migration has
            // completed the plaintext file is empty, so this returns an EMPTY store and any writes
            // the user makes during the degraded session land UNENCRYPTED on disk. Those writes are
            // recovered on the next healthy launch (see the completion marker in migratePlaintext),
            // but they were still written in the clear.
            //
            // Failing open like this is a deliberate availability-over-confidentiality trade-off
            // that predates this change and is tracked separately; it is logged loudly so the
            // degradation is at least visible in a bug report.
            Log.e(TAG, "Encrypted prefs unavailable for '$name'; falling back to plaintext", e)
            return appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        }

        synchronized(locks.getOrPut(name) { Any() }) {
            try {
                migratePlaintext(
                    name = name,
                    plaintext = appContext.getSharedPreferences(name, Context.MODE_PRIVATE),
                    encrypted = encrypted,
                )
            } catch (e: Exception) {
                // Deliberately swallowed: a migration hiccup (a corrupt legacy entry, an I/O error)
                // must not downgrade a perfectly healthy encrypted store to plaintext for the rest
                // of the session. Nothing has been destroyed — the plaintext file is only cleared
                // after a successful encrypted commit — so the next launch retries.
                Log.e(TAG, "Plaintext migration failed for '$name'; will retry next launch", e)
            }
        }
        return encrypted
    }

    /**
     * Copies values from the legacy [plaintext] prefs file into [encrypted], then clears the
     * plaintext copy.
     *
     * **Durability** (issue #24): the encrypted write uses the *synchronous*
     * [SharedPreferences.Editor.commit], and the plaintext file is cleared only after that commit
     * reports success. `apply()` merely enqueues the write and returns, so clearing the plaintext
     * right after an `apply()` leaves a window in which process death (OOM kill, crash, force-stop)
     * destroys the only surviving copy of the data. This blocks the calling thread — in practice
     * the main thread, since the stores are constructed lazily from ViewModels — which is accepted
     * because it runs at most once per store and the payloads are small. `EncryptedSharedPreferences
     * .create` and `plaintext.all` on the same path already do blocking I/O. Once the marker below
     * is set and the plaintext file is empty, this performs no writes at all.
     *
     * **Conflict resolution.** Writes happen in three ordered, synchronous steps: commit the values
     * into [encrypted], clear [plaintext], then commit [MIGRATION_COMPLETE_KEY]. The marker is what
     * distinguishes the two ways plaintext values can be present when this runs:
     * - *Marker absent* — an upgrade, a retry of a migration whose plaintext clear never landed,
     *   or (rarely) a completed migration whose marker commit failed. Values already present in
     *   [encrypted] are newer, so plaintext entries for those keys are skipped. New keys are still
     *   copied, so nothing is lost.
     * - *Marker present* — the plaintext file was written **after** a completed migration, which
     *   only happens when [create] fell back to the plaintext store for a session. That session saw
     *   an empty store, so its plaintext file is the complete authoritative view: the encrypted
     *   contents are **replaced** rather than merged, so keys the session deleted stay deleted.
     *   (Without this the fallback session's data would be silently destroyed by the clear below.)
     *
     * The marker must be committed *after* the clear. Committing it earlier would make an
     * interrupted retry treat a stale, uncleared plaintext file as newer than the encrypted values.
     *
     * Any step failing simply leaves the migration to be retried on the next launch, with no data
     * destroyed in the meantime.
     *
     * Known assumption: a non-empty plaintext file alongside the marker is taken to be newer. A
     * backup/restore that reintroduces a stale plaintext file next to a readable encrypted store
     * would violate that; excluding these prefs from backup is tracked as issue #9.
     *
     * Visible for testing; call sites should use [create].
     */
    internal fun migratePlaintext(
        name: String,
        plaintext: SharedPreferences,
        encrypted: SharedPreferences,
    ) {
        val migrationComplete = encrypted.getBoolean(MIGRATION_COMPLETE_KEY, false)
        val entries = plaintext.all

        if (entries.isEmpty()) {
            // Nothing to migrate. Still record completion, so that if a later session falls back to
            // the plaintext store and writes to it, the branch above recognises those values as
            // newer than the encrypted ones instead of discarding them.
            markMigrationComplete(name, encrypted, migrationComplete)
            return
        }

        val editor = encrypted.edit()
        if (migrationComplete) {
            // Recovering a fallback session: replace, don't merge (see KDoc). clear() drops the
            // marker too, so put it back in the same editor to keep the commit atomic.
            editor.clear().putBoolean(MIGRATION_COMPLETE_KEY, true)
        }

        var migrated = 0
        var skipped = 0
        for ((key, value) in entries) {
            if (key == MIGRATION_COMPLETE_KEY) continue // reserved; never accept it from plaintext
            if (!migrationComplete && encrypted.contains(key)) {
                // Retry of an interrupted migration: the encrypted value is the source of truth.
                skipped++
                continue
            }
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    if (value.all { it is String }) {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>)
                    } else {
                        Log.w(TAG, "skipping key '$key' with non-String set members")
                        skipped++
                        continue
                    }
                }
                else -> {
                    Log.w(TAG, "skipping unmigratable key '$key' of ${value?.javaClass}")
                    skipped++
                    continue
                }
            }
            migrated++
        }
        Log.d(TAG, "migrating $migrated plaintext entries for '$name' ($skipped skipped)")

        // Step 1 — synchronous: must be durable on disk before the plaintext copy is destroyed.
        if (!editor.commit()) {
            Log.e(TAG, "encrypted commit failed for '$name'; keeping plaintext for a later retry")
            return
        }

        // Step 2 — safe to drop the plaintext copy now. Also synchronous, so a failure is
        // observable; if it fails the data is still safe in the encrypted store and the next launch
        // retries (re-migration is a no-op thanks to the marker/contains guards above).
        if (!plaintext.edit().clear().commit()) {
            Log.w(TAG, "failed to clear legacy plaintext prefs for '$name'; will retry next launch")
            return
        }

        // Step 3 — only now is the migration complete.
        markMigrationComplete(name, encrypted, migrationComplete)
    }

    private fun markMigrationComplete(
        name: String,
        encrypted: SharedPreferences,
        alreadyMarked: Boolean,
    ) {
        if (alreadyMarked) return
        // Retried once: leaving the marker unset after the plaintext file has been cleared would
        // make a later fallback session's writes look like stale pre-migration data.
        repeat(2) { attempt ->
            if (encrypted.edit().putBoolean(MIGRATION_COMPLETE_KEY, true).commit()) return
            Log.w(TAG, "failed to record migration completion for '$name' (attempt ${attempt + 1})")
        }
    }
}
