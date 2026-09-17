package com.jaustinjr.employeeattendance.statusupdate

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented rather than unit: [Intent] extras are backed by a real `Bundle`, which the JVM unit
 * test config (`isReturnDefaultValues = true`) stubs to defaults rather than genuinely storing and
 * retrieving values. See CLAUDE.md's "Why the JVM layer alone is not enough".
 */
class StatusUpdateIntentsTest {

    @Test
    fun consumeRequestOfANullIntentIsNull() {
        assertNull(StatusUpdateIntents.consumeRequest(null))
    }

    @Test
    fun consumeRequestOfAnIntentWithNoExtrasIsNull() {
        assertNull(StatusUpdateIntents.consumeRequest(Intent()))
    }

    @Test
    fun putExtrasThenConsumeRequestRoundTripsTheRequest() {
        val request = StatusUpdateRequest("site-a", 1_000L)
        val intent = StatusUpdateIntents.putExtras(Intent(), request)

        assertEquals(request, StatusUpdateIntents.consumeRequest(intent))
    }

    @Test
    fun consumeRequestRemovesTheExtrasSoAReplayReturnsNull() {
        val request = StatusUpdateRequest("site-a", 1_000L)
        val intent = StatusUpdateIntents.putExtras(Intent(), request)

        StatusUpdateIntents.consumeRequest(intent)

        assertFalse(intent.hasExtra(StatusUpdateIntents.EXTRA_LOCATION_ID))
        assertFalse(intent.hasExtra(StatusUpdateIntents.EXTRA_CLOCK_OUT_AT))
        assertNull(StatusUpdateIntents.consumeRequest(intent))
    }

    @Test
    fun consumeRequestIgnoresARelaunchFromHistory() {
        val request = StatusUpdateRequest("site-a", 1_000L)
        val intent = StatusUpdateIntents.putExtras(Intent(), request).apply {
            addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        }

        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0)
        assertNull(StatusUpdateIntents.consumeRequest(intent))
    }
}
