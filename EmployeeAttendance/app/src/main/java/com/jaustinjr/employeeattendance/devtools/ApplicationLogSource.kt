package com.jaustinjr.employeeattendance.devtools

import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/**
 * Reads back the application's own log. This is the seam the log export is written against, so the
 * export flow can be tested without spawning a process.
 *
 * The app logs freely through [android.util.Log] on every layer (see the `TAG` constants throughout
 * the codebase), so rather than retro-fitting a logging facade over every call site, the default
 * implementation reads those same lines back out of the platform's ring buffer.
 */
interface ApplicationLogSource {

    /**
     * Returns the most recent log lines for this app, oldest first, newline-separated.
     *
     * @param maxLines cap on the number of returned lines; the oldest beyond it are dropped.
     * @throws java.io.IOException if the log cannot be read.
     */
    suspend fun read(maxLines: Int = DEFAULT_MAX_LINES): String

    companion object {
        /** Enough to cover a session's worth of location/proximity chatter without a huge attachment. */
        const val DEFAULT_MAX_LINES = 2_000
    }
}

/**
 * [ApplicationLogSource] backed by `logcat`.
 *
 * Since Android 4.1 an app reading logcat without `READ_LOGS` sees only its *own* process's
 * output, which is exactly the scope wanted here — no other app's logs can end up in an exported
 * file. `--pid` (API 24+, matching the module's `minSdk`) makes that explicit rather than relying on
 * the platform filter; if a device's `logcat` rejects the flag we retry without it rather than
 * failing the export.
 */
class LogcatApplicationLogSource(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApplicationLogSource {

    override suspend fun read(maxLines: Int): String = withContext(ioDispatcher) {
        require(maxLines > 0) { "maxLines must be positive: $maxLines" }
        val lines = runCatching { dump(withPid = true) }
            .getOrElse { error ->
                Log.w(TAG, "logcat --pid failed; retrying unfiltered", error)
                dump(withPid = false)
            }
        lines.takeLast(maxLines).joinToString(separator = "\n")
    }

    private fun dump(withPid: Boolean): List<String> {
        val command = buildList {
            addAll(BASE_COMMAND)
            if (withPid) add("--pid=${Process.myPid()}")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        return try {
            process.inputStream.bufferedReader().use(BufferedReader::readLines)
        } finally {
            // -d makes logcat dump and exit, but destroy() covers a device that ignores that.
            process.destroy()
        }
    }

    private companion object {
        const val TAG = "DevLogSource"

        /** `-d` dumps and exits; `-v time` gives human-readable timestamps in the attachment. */
        val BASE_COMMAND = listOf("logcat", "-d", "-v", "time")
    }
}
