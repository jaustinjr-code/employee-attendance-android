package com.jaustinjr.employeeattendance.statusupdate

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredStatusUpdateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String): List<StatusUpdate> =
        json.decodeFromString<List<StoredStatusUpdate>>(raw).map { it.toDomain() }

    /** Exactly what the release before the question-keyed format wrote. */
    private val legacyJson = """[{"clockOutId":"site-a@1000","didToday":"Wrote the report",""" +
        """"plannedTomorrow":"","couldNotDo":"Deliveries","completedAtMillis":2000,""" +
        """"clockOutAtMillis":1000,"clockInAtMillis":500,"worksiteName":"Main office",""" +
        """"editedAtMillis":3000}]"""

    @Test
    fun `legacy json with the three named fields loads into the answers map`() {
        val update = decode(legacyJson).single()

        assertEquals("site-a@1000", update.clockOutId)
        assertEquals("Wrote the report", update.answers[StatusUpdateQuestion.DID_TODAY])
        assertEquals("", update.answers[StatusUpdateQuestion.PLANNED_TOMORROW])
        assertEquals("Deliveries", update.answers[StatusUpdateQuestion.COULD_NOT_DO])
        assertEquals(2_000L, update.completedAtMillis)
        assertEquals(1_000L, update.clockOutAtMillis)
        assertEquals(500L, update.clockInAtMillis)
        assertEquals("Main office", update.worksiteName)
        assertEquals(3_000L, update.editedAtMillis)
    }

    @Test
    fun `new format round-trips and no longer writes the legacy fields`() {
        val update = StatusUpdate(
            clockOutId = "a@1",
            answers = mapOf(
                StatusUpdateQuestion.DID_TODAY to "one",
                StatusUpdateQuestion.COULD_NOT_DO to "three",
            ),
            completedAtMillis = 5L,
            worksiteName = "Depot",
        )

        val raw = json.encodeToString(listOf(update.toStored()))

        assertEquals(listOf(update), decode(raw))
        assertTrue(raw.contains("\"did_today\""))
        assertFalse(raw.contains("didToday"))
        assertFalse(raw.contains("couldNotDo"))
    }

    @Test
    fun `unknown question ids are ignored`() {
        val raw = """[{"clockOutId":"a@1","answers":{"did_today":"x","retired":"y"},"completedAtMillis":1}]"""

        assertEquals(mapOf(StatusUpdateQuestion.DID_TODAY to "x"), decode(raw).single().answers)
    }

    @Test
    fun `a non-empty answers map wins over legacy fields`() {
        val raw = """[{"clockOutId":"a@1","answers":{"did_today":"new"},"didToday":"old","completedAtMillis":1}]"""

        assertEquals(mapOf(StatusUpdateQuestion.DID_TODAY to "new"), decode(raw).single().answers)
    }

    @Test
    fun `missing keys mean unanswered`() {
        val raw = """[{"clockOutId":"a@1","couldNotDo":"only this","completedAtMillis":1}]"""

        val update = decode(raw).single()

        assertEquals(mapOf(StatusUpdateQuestion.COULD_NOT_DO to "only this"), update.answers)
        assertNull(update.answers[StatusUpdateQuestion.DID_TODAY])
        assertTrue(decode("""[{"clockOutId":"a@1","completedAtMillis":1}]""").single().answers.isEmpty())
    }
}
