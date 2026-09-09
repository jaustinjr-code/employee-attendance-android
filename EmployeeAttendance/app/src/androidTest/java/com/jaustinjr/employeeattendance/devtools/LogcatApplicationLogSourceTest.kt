package com.jaustinjr.employeeattendance.devtools

import android.util.Log
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented because it spawns `logcat`; on the JVM the framework stubs make both the writes and
 * the read no-ops, so this could never fail there.
 */
class LogcatApplicationLogSourceTest {

    private val source = LogcatApplicationLogSource()

    @Test
    fun readsBackThisProcessesOwnLogLines() = runTest {
        val marker = "dev-log-marker-${System.nanoTime()}"
        Log.i(TAG, marker)

        val dump = source.read()

        assertTrue("marker not found in ${dump.length} chars of log", dump.contains(marker))
    }

    @Test
    fun capsTheReturnedLineCount() = runTest {
        repeat(40) { Log.i(TAG, "filler line $it") }

        val dump = source.read(maxLines = 10)

        assertTrue(dump, dump.count { it == '\n' } < 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsANonPositiveLineCap() = runTest {
        source.read(maxLines = 0)
    }

    private companion object {
        const val TAG = "LogcatSourceTest"
    }
}
