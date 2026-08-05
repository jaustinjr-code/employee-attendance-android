package com.jaustinjr.employeeattendance.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
     * Returns the encrypted prefs for [name], migrating any legacy plaintext values first. If
     * encryption is unavailable (e.g. a corrupt keystore), falls back to plaintext prefs so the app
     * still runs rather than crashing — logged so the degradation is visible.
     */
    fun create(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                appContext,
                "${name}_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migratePlaintext(
                name = name,
                plaintext = appContext.getSharedPreferences(name, Context.MODE_PRIVATE),
                encrypted = encrypted,
            )
            encrypted
        } catch (e: Exception) {
            // Either the keystore/encrypted store is unusable, or migration failed outright. In
            // both cases the legacy plaintext file is still intact (migratePlaintext only clears it
            // after a durable, successful encrypted commit), so handing back the plaintext store
            // keeps the user's data reachable instead of presenting an empty app. Logged loudly
            // because it means sensitive data is being stored unencrypted.
            Log.e(TAG, "Encrypted prefs unavailable for '$name'; falling back to plaintext", e)
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    /**
     * Copies values from the legacy [plaintext] prefs file into [encrypted], then clears the
     * plaintext copy.
     *
     * Durability contract (see issue #24): the encrypted write uses the **synchronous** [
     * SharedPreferences.Editor.commit], and the plaintext file is cleared only after that commit
     * reports success. `apply()` merely enqueues the write and returns, so clearing the plaintext
     * right after an `apply()` leaves a window in which process death (OOM kill, crash, force-stop)
     * destroys the only surviving copy of the data. Blocking here is acceptable: this runs at most
     * once per store, on the first launch after upgrading.
     *
     * If the encrypted commit fails, nothing is cleared and the migration is simply retried on the
     * next launch. Keys already present in [encrypted] are never overwritten, which makes those
     * retries safe: once a value lives in the encrypted store it is the source of truth, so a
     * leftover (stale) plaintext copy can't clobber newer data.
     *
     * Visible for testing; call sites should use [create].
     */
    internal fun migratePlaintext(
        name: String,
        plaintext: SharedPreferences,
        encrypted: SharedPreferences,
    ) {
        val entries = plaintext.all
        if (entries.isEmpty()) return

        val editor = encrypted.edit()
        var migrated = 0
        for ((key, value) in entries) {
            if (encrypted.contains(key)) {
                // Already migrated by an earlier attempt whose plaintext clear didn't land; the
                // encrypted value wins so a retry can't resurrect stale data.
                Log.d(TAG, "skipping key '$key' for '$name'; already present in encrypted store")
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
                        continue
                    }
                }
                else -> {
                    Log.w(TAG, "skipping unmigratable key '$key' of ${value?.javaClass}")
                    continue
                }
            }
            migrated++
        }
        Log.d(TAG, "migrating $migrated plaintext entries for '$name' into encrypted store")

        // Synchronous: must be durable on disk before the plaintext copy is destroyed.
        if (!editor.commit()) {
            Log.e(TAG, "encrypted commit failed for '$name'; keeping plaintext for a later retry")
            return
        }

        // Safe to drop the plaintext copy now. Also synchronous, so a failure is observable; if it
        // fails the data is still safe in the encrypted store and the next launch retries the
        // clear (re-migration is a no-op thanks to the contains() guard above).
        if (!plaintext.edit().clear().commit()) {
            Log.w(TAG, "failed to clear legacy plaintext prefs for '$name'; will retry next launch")
        }
    }
}
