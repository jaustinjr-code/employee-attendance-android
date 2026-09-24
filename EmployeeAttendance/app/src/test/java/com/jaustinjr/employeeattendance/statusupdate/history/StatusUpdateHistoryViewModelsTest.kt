package com.jaustinjr.employeeattendance.statusupdate.history

import androidx.lifecycle.SavedStateHandle
import com.jaustinjr.employeeattendance.statusupdate.DefaultStatusUpdateRepository
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdate
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateQuestion
import com.jaustinjr.employeeattendance.statusupdate.toDrafts
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusUpdateHistoryViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = DefaultStatusUpdateRepository().apply {
        save(
            StatusUpdate(
                clockOutId = "site-a@1000",
                answers = mapOf(
                    StatusUpdateQuestion.DID_TODAY to "Wrote the report",
                    StatusUpdateQuestion.COULD_NOT_DO to "Deliveries",
                ),
                completedAtMillis = 2_000L,
                clockOutAtMillis = 1_000L,
            ),
        )
    }

    private fun handle(id: String = "site-a@1000") = SavedStateHandle(mapOf(CLOCK_OUT_ID_ARG to id))

    private fun editViewModel(handle: SavedStateHandle = handle()) =
        StatusUpdateEditViewModel(handle, repository, clock = { 7_000L })

    // --- history ---

    @Test
    fun `history reflects repository changes`() = runTest {
        val viewModel = StatusUpdateHistoryViewModel(repository) { TimeZone.getTimeZone("UTC") }
        backgroundScope.launch { viewModel.days.collect {} }
        runCurrent()
        assertEquals(1, viewModel.days.value.single().shifts.size)

        repository.clearAll()
        runCurrent()

        assertTrue(viewModel.days.value.isEmpty())
    }

    // --- detail ---

    @Test
    fun `detail shows the named shift and follows edits`() = runTest {
        val viewModel = StatusUpdateDetailViewModel(handle(), repository)
        backgroundScope.launch { viewModel.shift.collect {} }
        runCurrent()
        assertEquals("Wrote the report", viewModel.shift.value?.previewText)

        repository.updateAnswers("site-a@1000", mapOf(StatusUpdateQuestion.DID_TODAY to "Rewrote the report"), 5_000L)
        runCurrent()

        assertEquals("Rewrote the report", viewModel.shift.value?.previewText)
        assertEquals(5_000L, viewModel.shift.value?.editedAtMillis)
    }

    @Test
    fun `detail for an unknown shift is null`() {
        assertNull(StatusUpdateDetailViewModel(handle("missing@1"), repository).shift.value)
    }

    // --- edit ---

    @Test
    fun `editing starts from the saved answers, including unanswered questions`() {
        val state = editViewModel().uiState.value

        assertEquals(listOf("Wrote the report", "", "Deliveries"), state.drafts)
        assertTrue(state.found)
        assertTrue(state.canSave)
        assertFalse(state.showDiscardDialog)
    }

    @Test
    fun `save writes the drafts back with the edit time`() {
        val viewModel = editViewModel()
        viewModel.onDraftChanged(1, "Restock shelves")

        assertTrue(viewModel.save())

        val saved = repository.statusUpdates.value.single()
        assertEquals(listOf("Wrote the report", "Restock shelves", "Deliveries"), saved.answers.toDrafts())
        assertEquals(7_000L, saved.editedAtMillis)
    }

    @Test
    fun `clearing every answer blocks saving`() {
        val viewModel = editViewModel()
        viewModel.onDraftChanged(0, "")
        viewModel.onDraftChanged(2, "  ")

        assertFalse(viewModel.uiState.value.canSave)
        assertFalse(viewModel.save())
        assertEquals("Wrote the report", repository.statusUpdates.value.single().answers[StatusUpdateQuestion.DID_TODAY])
    }

    @Test
    fun `exiting always asks, even with no text changed, and keep editing closes the dialog`() {
        val viewModel = editViewModel()

        viewModel.onExitRequested()
        assertTrue(viewModel.uiState.value.showDiscardDialog)

        viewModel.onDiscardDialogDismissed()
        assertFalse(viewModel.uiState.value.showDiscardDialog)
    }

    @Test
    fun `discarding confirmed closes the dialog and signals exit`() {
        val viewModel = editViewModel()
        viewModel.onExitRequested()
        assertTrue(viewModel.uiState.value.showDiscardDialog)
        assertFalse(viewModel.exitConfirmed.value)

        viewModel.onDiscardConfirmed()

        assertFalse(viewModel.uiState.value.showDiscardDialog)
        assertTrue(viewModel.exitConfirmed.value)
    }

    @Test
    fun `onExitHandled resets exitConfirmed so a later discard is not swallowed`() {
        val viewModel = editViewModel()
        viewModel.onExitRequested()
        viewModel.onDiscardConfirmed()
        assertTrue(viewModel.exitConfirmed.value)

        viewModel.onExitHandled()

        assertFalse(viewModel.exitConfirmed.value)
    }

    @Test
    fun `drafts and the dialog survive recreating the view model from saved state`() {
        val handle = handle()
        editViewModel(handle).apply {
            onDraftChanged(0, "Unsaved draft")
            onExitRequested()
        }

        val recreated = editViewModel(handle).uiState.value

        assertEquals("Unsaved draft", recreated.drafts[0])
        assertTrue(recreated.showDiscardDialog)
        assertEquals("Wrote the report", repository.statusUpdates.value.single().answers[StatusUpdateQuestion.DID_TODAY])
    }

    @Test
    fun `editing an unknown shift cannot save`() {
        val viewModel = editViewModel(handle("missing@1"))

        assertFalse(viewModel.uiState.value.found)
        assertFalse(viewModel.save())
    }
}
