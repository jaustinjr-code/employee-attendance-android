package com.jaustinjr.employeeattendance.storage

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] double for pure-JVM tests.
 *
 * Deliberately models the durability difference that issue #24 is about:
 * - [SharedPreferences.Editor.commit] writes through to [durable] and reports success/failure.
 * - [SharedPreferences.Editor.apply] does **not** write through. It only records that it was
 *   called, standing in for "the write was enqueued and the process died before it landed."
 *
 * Every mutating call is appended to the shared [log] so tests can assert ordering across two
 * stores (e.g. that the encrypted commit happens before the plaintext clear).
 */
class FakeSharedPreferences(
    private val label: String,
    private val log: MutableList<String> = mutableListOf(),
    initial: Map<String, Any?> = emptyMap(),
) : SharedPreferences {

    /** Values that have actually reached "disk" (i.e. survived a process kill). */
    val durable: MutableMap<String, Any?> = LinkedHashMap(initial)

    /**
     * When false, every [SharedPreferences.Editor.commit] on this store fails and writes nothing.
     *
     * Divergence from the platform: real `SharedPreferencesImpl` updates its in-memory map first
     * and returns false only for the *disk* write, so same-process reads after a failed commit see
     * the new values. This double models the next-process view (reload from disk), which is the
     * state the migration's retry path actually has to cope with.
     */
    var commitSucceeds: Boolean = true

    override fun getAll(): MutableMap<String, Any?> = LinkedHashMap(durable)

    // Wrong-type reads throw, matching SharedPreferencesImpl, so a type-confusion bug in the
    // migration surfaces as a failure rather than a silently-returned default.
    override fun getString(key: String?, defValue: String?): String? =
        durable[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (durable[key] as Set<String>?)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = durable[key] as Int? ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = durable[key] as Long? ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        durable[key] as Float? ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        durable[key] as Boolean? ?: defValue

    override fun contains(key: String?): Boolean = durable.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            also { pending[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = also { pending[key] = values?.toSet() }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            also { pending[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            also { pending[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            also { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            also { pending[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = also { removals += key }

        override fun clear(): SharedPreferences.Editor = also { clearRequested = true }

        override fun commit(): Boolean {
            log += "$label.commit"
            if (!commitSucceeds) return false
            flush()
            return true
        }

        /** Records the call but intentionally does not make the write durable. */
        override fun apply() {
            log += "$label.apply"
        }

        private fun flush() {
            if (clearRequested) durable.clear()
            removals.forEach { durable.remove(it) }
            durable.putAll(pending)
        }
    }
}
