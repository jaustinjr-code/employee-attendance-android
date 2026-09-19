package com.jaustinjr.employeeattendance.statusupdate

import androidx.annotation.StringRes
import com.jaustinjr.employeeattendance.R

/**
 * The single definition of the Status Update question set. Everything that lists, counts or orders
 * questions (card deck, edit screen, history, detail) derives from [entries].
 *
 * - **Declaration order is the display order.** Reordering entries reorders the deck, the edit
 *   screen and history; nothing else encodes an order.
 * - **[id] is persisted** as the key of stored answers. Never rename or reuse an id. To add a
 *   question, add a new entry with a new id (plus its strings); to retire one, remove the entry and
 *   leave its id unused: stored answers under unknown ids are ignored on load.
 */
enum class StatusUpdateQuestion(
    val id: String,
    @StringRes val questionRes: Int,
    @StringRes val hintRes: Int,
) {
    DID_TODAY(
        "did_today",
        R.string.status_update_question_did_today,
        R.string.status_update_hint_did_today,
    ),
    PLANNED_TOMORROW(
        "planned_tomorrow",
        R.string.status_update_question_planned_tomorrow,
        R.string.status_update_hint_planned_tomorrow,
    ),
    COULD_NOT_DO(
        "could_not_do",
        R.string.status_update_question_could_not_do,
        R.string.status_update_hint_could_not_do,
    );

    companion object {
        /** How many questions there are; derived, never a literal. */
        val COUNT: Int get() = entries.size

        /** The question stored under [id], or null for an id this build does not know. */
        fun fromId(id: String): StatusUpdateQuestion? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Converts editable drafts (one string per question, in [StatusUpdateQuestion.entries] order; the
 * Bundle-friendly shape the UI keeps) to answers. Blank drafts are left out: a missing key means
 * unanswered. This and [toDrafts] are the only place drafts are mapped to questions by position.
 */
fun List<String>.toAnswers(): Map<StatusUpdateQuestion, String> {
    require(size == StatusUpdateQuestion.COUNT) {
        "expected ${StatusUpdateQuestion.COUNT} drafts, got $size"
    }
    return StatusUpdateQuestion.entries.withIndex()
        .filter { (index, _) -> this[index].isNotBlank() }
        .associate { (index, question) -> question to this[index] }
}

/** Answers as editable drafts in [StatusUpdateQuestion.entries] order; unanswered becomes "". */
fun Map<StatusUpdateQuestion, String>.toDrafts(): List<String> =
    StatusUpdateQuestion.entries.map { this[it].orEmpty() }

/** Drafts with nothing written, one per question. */
fun emptyDrafts(): List<String> = List(StatusUpdateQuestion.COUNT) { "" }
