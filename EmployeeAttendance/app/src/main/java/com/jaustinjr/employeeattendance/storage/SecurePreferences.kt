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
     * to the legacy plaintext prefs **read-only** ([ReadOnlyPreferences]) so the app still runs and
     * still shows existing data, but never writes personal location data to disk unencrypted —
     * logged so the degradation is visible. A failure *inside the migration* never costs
     * encryption: the encrypted store is returned regardless and the migration is retried on the
     * next launch.
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
            // Fails CLOSED. The legacy plaintext file is served read-only, so a pre-migration
            // install still starts up with its worksites and attendance intact, but nothing new is
            // ever written to it. Writes are rejected rather than persisted in the clear: worksite
            // coordinates and attendance history are personal location data, and a durable
            // unencrypted copy of them is a worse outcome than a session that cannot save.
            //
            // The cost is real and deliberate — a degraded session loses whatever the user does in
            // it (see [RejectingEditor]). Surfacing that to the user as an explicit error state,
            // rather than only in the log, is the other half of this and is not done here.
            Log.e(TAG, "Encrypted prefs unavailable for '$name'; serving legacy values read-only", e)
            return ReadOnlyPreferences(name, appContext.getSharedPreferences(name, Context.MODE_PRIVATE))
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
     * **Entries that cannot be migrated** (an unsupported value type, or a `Set` with non-`String`
     * members — reachable only from a corrupt or tampered legacy file, since nothing in this app
     * writes them) are left on disk instead of being cleared. They were never copied into
     * [encrypted], so the plaintext file is their only copy. The migration then deliberately stays
     * incomplete: the marker's premise is that a non-empty plaintext file can only be a fallback
     * session's work, and leftover junk would break that. Leaving the marker unset keeps the safe
     * merge semantics on every later launch, at the cost of the replace-on-recovery path while such
     * entries remain.
     *
     * Known assumption: a non-empty plaintext file alongside the marker is taken to be newer. A
     * backup/restore that reintroduces a stale plaintext file next to a readable encrypted store
     * would violate that; excluding these prefs from backup is tracked as issue #9.
     *
     * Note that [create] now serves the plaintext fallback read-only, so no *new* fallback session
     * can produce plaintext writes. The marker-present recovery path is retained for installs that
     * already accumulated them under earlier builds.
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

        // Classify before touching either store. Two later decisions depend on knowing up front
        // whether anything here is actually migratable: the replace-vs-merge branch must not clear
        // the encrypted store on the strength of a plaintext file holding nothing it can replace it
        // with, and step 2 must not delete an entry that was never copied anywhere.
        val migratable = LinkedHashMap<String, Any>()
        val unmigratable = mutableSetOf<String>()
        for ((key, value) in entries) {
            if (key == MIGRATION_COMPLETE_KEY) continue // reserved; never accept it from plaintext
            when {
                value is String || value is Boolean || value is Int ||
                    value is Long || value is Float -> migratable[key] = value
                value is Set<*> && value.all { it is String } -> migratable[key] = value
                value is Set<*> -> {
                    Log.w(TAG, "cannot migrate key '$key': non-String set members")
                    unmigratable += key
                }
                else -> {
                    Log.w(TAG, "cannot migrate key '$key' of ${value?.javaClass}")
                    unmigratable += key
                }
            }
        }

        if (migratable.isEmpty() && unmigratable.isEmpty()) {
            // Nothing to migrate. Still record completion, so that if a later session falls back to
            // the plaintext store and writes to it, the branch above recognises those values as
            // newer than the encrypted ones instead of discarding them.
            markMigrationComplete(name, encrypted, migrationComplete)
            return
        }

        val editor = encrypted.edit()
        if (migrationComplete && migratable.isNotEmpty()) {
            // Recovering a fallback session: replace, don't merge (see KDoc). clear() drops the
            // marker too, so put it back in the same editor to keep the commit atomic. Guarded on
            // there being something to replace with: a plaintext file left holding only
            // unmigratable junk is not an authoritative view of anything.
            editor.clear().putBoolean(MIGRATION_COMPLETE_KEY, true)
        }

        var migrated = 0
        var superseded = 0
        for ((key, value) in migratable) {
            if (!migrationComplete && encrypted.contains(key)) {
                // Retry of an interrupted migration: the encrypted value is the source of truth.
                superseded++
                continue
            }
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
            migrated++
        }
        Log.d(
            TAG,
            "migrating $migrated plaintext entries for '$name' " +
                "($superseded superseded, ${unmigratable.size} unmigratable)",
        )

        // Step 1 — synchronous: must be durable on disk before the plaintext copy is destroyed.
        if (!editor.commit()) {
            Log.e(TAG, "encrypted commit failed for '$name'; keeping plaintext for a later retry")
            return
        }

        // Step 2 — safe to drop the plaintext copy now. Also synchronous, so a failure is
        // observable; if it fails the data is still safe in the encrypted store and the next launch
        // retries (re-migration is a no-op thanks to the marker/contains guards above).
        //
        // Anything we could not migrate is LEFT BEHIND rather than cleared: it was never written to
        // the encrypted store, so the plaintext file is its only copy and a blanket clear() would
        // destroy it outright. Only entries that are now safely encrypted (or already were) are
        // removed. A forged MIGRATION_COMPLETE_KEY is dropped along with them.
        val plaintextEditor = plaintext.edit()
        if (unmigratable.isEmpty()) {
            plaintextEditor.clear()
        } else {
            entries.keys.filterNot { it in unmigratable }.forEach(plaintextEditor::remove)
        }
        if (!plaintextEditor.commit()) {
            Log.w(TAG, "failed to clear legacy plaintext prefs for '$name'; will retry next launch")
            return
        }

        // Step 3 — only now is the migration complete. Leftover unmigratable entries mean it is
        // not: the marker's premise is that a non-empty plaintext file can only be a fallback
        // session's work, and leaving the marker unset keeps the safe merge semantics on every
        // later launch instead. Those entries are preserved, not silently adopted.
        if (unmigratable.isEmpty()) {
            markMigrationComplete(name, encrypted, migrationComplete)
        } else {
            Log.w(
                TAG,
                "'$name' keeps ${unmigratable.size} unmigratable plaintext entries; " +
                    "migration stays incomplete so they are never treated as authoritative",
            )
        }
    }

    /**
     * Read-only view over the legacy plaintext prefs, returned by [create] when the encrypted store
     * cannot be constructed.
     *
     * Reads are delegated so a pre-migration install still sees its existing values; [edit] hands
     * back an editor that persists nothing. See the fallback branch in [create] for why this fails
     * closed rather than writing personal location data to disk unencrypted.
     */
    internal class ReadOnlyPreferences(
        private val name: String,
        delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = RejectingEditor(name)
    }

    /**
     * Editor that accepts every mutation call and persists none of them.
     *
     * [commit] returns `false` — the contract's "the write did not happen" signal — and [apply],
     * which has no return value, simply drops the batch. Callers are not expected to recover; this
     * exists so a degraded session degrades quietly instead of crashing, with the loss recorded in
     * the log rather than written to disk in the clear.
     */
    private class RejectingEditor(private val name: String) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = reject()
        override fun putStringSet(key: String?, values: MutableSet<String>?) = reject()
        override fun putInt(key: String?, value: Int) = reject()
        override fun putLong(key: String?, value: Long) = reject()
        override fun putFloat(key: String?, value: Float) = reject()
        override fun putBoolean(key: String?, value: Boolean) = reject()
        override fun remove(key: String?) = reject()
        override fun clear() = reject()

        override fun commit(): Boolean {
            logRejection()
            return false
        }

        override fun apply() = logRejection()

        private fun reject(): SharedPreferences.Editor = this

        private fun logRejection() {
            Log.e(TAG, "refusing to write '$name' unencrypted; this session's changes are not saved")
        }
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
