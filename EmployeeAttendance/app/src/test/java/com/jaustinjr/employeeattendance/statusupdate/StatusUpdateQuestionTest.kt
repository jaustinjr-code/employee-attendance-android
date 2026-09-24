package com.jaustinjr.employeeattendance.statusupdate

import com.jaustinjr.employeeattendance.statusupdate.ui.StatusUpdateCardStackUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusUpdateQuestionTest {

    @Test
    fun `persisted ids are stable and unique`() {
        assertEquals(
            listOf("did_today", "planned_tomorrow", "could_not_do"),
            StatusUpdateQuestion.entries.map { it.id },
        )
        assertEquals(StatusUpdateQuestion.COUNT, StatusUpdateQuestion.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `fromId resolves every entry and rejects unknown ids`() {
        StatusUpdateQuestion.entries.forEach {
            assertEquals(it, StatusUpdateQuestion.fromId(it.id))
        }
        assertNull(StatusUpdateQuestion.fromId("retired_question"))
    }

    @Test
    fun `card count, empty drafts and card stack state all follow the enum`() {
        assertEquals(StatusUpdateQuestion.entries.size, StatusUpdateCardStackUiState.CARD_COUNT)
        assertEquals(StatusUpdateQuestion.entries.size, emptyDrafts().size)
        val state = StatusUpdateCardStackUiState(emptyDrafts(), currentIndex = 0)
        assertEquals(StatusUpdateQuestion.entries.size, state.drafts.size)
    }

    @Test
    fun `drafts and answers round-trip for every question`() {
        StatusUpdateQuestion.entries.forEachIndexed { index, question ->
            val drafts = emptyDrafts().toMutableList().also { it[index] = "text $index" }

            val answers = drafts.toAnswers()

            assertEquals(mapOf(question to "text $index"), answers)
            assertEquals(drafts, answers.toDrafts())
        }
        val full = StatusUpdateQuestion.entries.map { "x ${it.id}" }
        assertEquals(full, full.toAnswers().toDrafts())
    }

    @Test
    fun `blank drafts are unanswered and missing answers become empty drafts`() {
        assertTrue(emptyDrafts().toAnswers().isEmpty())
        assertTrue(List(StatusUpdateQuestion.COUNT) { "  " }.toAnswers().isEmpty())
        assertEquals(emptyDrafts(), emptyMap<StatusUpdateQuestion, String>().toDrafts())
    }
}
