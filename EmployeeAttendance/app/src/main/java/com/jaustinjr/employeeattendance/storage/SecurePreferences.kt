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
            migratePlaintext(appContext, name, encrypted)
            encrypted
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted prefs unavailable for '$name'; falling back to plaintext", e)
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    /** Copies values from a legacy plaintext prefs file into [encrypted], then clears the plaintext. */
    private fun migratePlaintext(
        context: Context,
        name: String,
        encrypted: SharedPreferences,
    ) {
        val plaintext = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val entries = plaintext.all
        if (entries.isEmpty()) return
        Log.d(TAG, "migrating ${entries.size} plaintext entries for '$name' into encrypted store")
        val editor = encrypted.edit()
        for ((key, value) in entries) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                else -> Log.w(TAG, "skipping unmigratable key '$key' of ${value?.javaClass}")
            }
        }
        editor.apply()
        plaintext.edit().clear().apply()
    }
}
