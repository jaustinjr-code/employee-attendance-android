package com.jaustinjr.employeeattendance.startup

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Seam over "the process has reached the foreground", so startup policy stays unit-testable without
 * a real Android lifecycle.
 *
 * This exists because of a hard platform rule: since Android 12 (API 31) `startForegroundService()`
 * throws `ForegroundServiceStartNotAllowedException` when the calling process is in the background,
 * and a process is still `PROCESS_STATE_CACHED_EMPTY` during `Application.onCreate()` — even on a
 * launcher tap. Anything that starts a foreground service has to wait behind this gate.
 */
interface ForegroundGate {

    /**
     * Runs [action] once, the first time the process is in the foreground. If the process is already
     * foreground, [action] runs as soon as the platform reports it; it is never run more than once.
     */
    fun onFirstForeground(action: () -> Unit)
}

/**
 * Default [ForegroundGate] backed by [ProcessLifecycleOwner], whose `ON_START` fires once the first
 * Activity reaches `STARTED`. By that point the uid has transitioned to a top/foreground process
 * state and a foreground-service start is permitted.
 *
 * Call [onFirstForeground] on the main thread; the callback is delivered there too.
 *
 * @param lifecycleProvider the lifecycle to observe. Defaults to the process lifecycle; overridden
 * in tests to drive a real [androidx.lifecycle.LifecycleRegistry] through the transitions this
 * class exists to react to.
 */
class ProcessLifecycleForegroundGate(
    private val lifecycleProvider: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle },
) : ForegroundGate {

    override fun onFirstForeground(action: () -> Unit) {
        val lifecycle = lifecycleProvider()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Removing an observer from inside its own callback is supported by LifecycleRegistry
                // and is what makes this fire exactly once rather than on every foreground return.
                lifecycle.removeObserver(this)
                Log.d(TAG, "process reached foreground; releasing gated startup work")
                action()
            }
        })
    }

    private companion object {
        const val TAG = "FgGate"
    }
}
