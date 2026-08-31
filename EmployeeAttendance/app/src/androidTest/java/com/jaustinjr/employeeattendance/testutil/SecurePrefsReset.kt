package com.jaustinjr.employeeattendance.testutil

import android.content.Context
import com.jaustinjr.employeeattendance.storage.SecurePreferences

/**
 * Clears the on-device state persisted under [name] by [SecurePreferences].
 *
 * `SecurePreferences.create(context, name)` stores values in an [androidx.security.crypto.EncryptedSharedPreferences]
 * file called `"${name}_secure"`, *not* in `getSharedPreferences(name, MODE_PRIVATE)`. Clearing only
 * the plaintext file leaves the real state behind, so persisted values leak between tests on a
 * reused emulator or app install. Both files are cleared here: the encrypted store because that is
 * where the data lives, and the legacy plaintext file because `SecurePreferences` migrates anything
 * found there into the encrypted store on next use.
 *
 * Uses `commit()` rather than `apply()` so the reset has landed before the test body runs.
 */
fun clearSecurePrefs(context: Context, name: String) {
    SecurePreferences.create(context, name).edit().clear().commit()
    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
}

/**
 * Clears the proximity state persisted by
 * [com.jaustinjr.employeeattendance.location.proximity.SharedPrefsProximityStateStore]. The name
 * mirrors that store's private `PREFS_NAME`.
 */
fun clearPersistedProximityState(context: Context) = clearSecurePrefs(context, "proximity_state")
