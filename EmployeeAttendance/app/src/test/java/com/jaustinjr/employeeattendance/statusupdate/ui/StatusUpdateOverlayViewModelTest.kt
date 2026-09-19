package com.jaustinjr.employeeattendance.statusupdate.ui

import com.jaustinjr.employeeattendance.attendance.RecordingAttendanceRepository
import com.jaustinjr.employeeattendance.statusupdate.DefaultStatusUpdateRepository
import com.jaustinjr.employeeattendance.statusupdate.FakeAppForegroundTracker
import com.jaustinjr.employeeattendance.statusupdate.RecordingStatusUpdateNotifier
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateCoordinator
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateQuestion
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateRequest
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateTrigger
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [StatusUpdateOverlayViewModel]. Exercises it against the real
 * [StatusUpdateCoordinator], wired to the shared fakes in `StatusUpdateTestDoubles.kt` for its own
 * platform seams (foreground tracking, notifications) and [RecordingAttendanceRepository] for the
 * clock-out log `claimNotificationRequest` validates against.
 *
 * `uiState` shares with `WhileSubscribed`, so — matching `WorksitesViewModelTest` — every test
 * collects it on `backgroundScope` and calls `runCurrent()` before reading `.value`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusUpdateOverlayViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var enabled: MutableStateFlow<Boolean>
    private lateinit var repository: DefaultStatusUpdateRepository
    private lateinit var attendance: RecordingAttendanceRepository
    private lateinit var notifier: RecordingStatusUpdateNotifier
    private lateinit var coordinator: StatusUpdateCoordinator
    private lateinit var viewModel: StatusUpdateOverlayViewModel

    private val locationId = "site-1"
    private val clockOutAtMillis = 1_000L

    @Before
    fun setUp() {
        enabled = MutableStateFlow(true)
        repository = DefaultStatusUpdateRepository()
        attendance = RecordingAttendanceRepository()
        notifier = RecordingStatusUpdateNotifier()
        coordinator = StatusUpdateCoordinator(
            enabled = enabled,
            foregroundTracker = FakeAppForegroundTracker(initial = true),
            notifier = notifier,
            repository = repository,
            attendanceRepository = attendance,
            clock = { 5_000L },
        )
        viewModel = StatusUpdateOverlayViewModel(coordinator)
    }

    private fun clockOut() {
        coordinator.onClockOut(locationId, clockOutAtMillis, StatusUpdateTrigger.MANUAL)
    }

    @Test
    fun beginPrompt_opensDeckAtFirstCard() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()

        viewModel.onBeginPrompt()
        runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.prompt)
        assertEquals(0, state.deck?.currentIndex)
        assertEquals(listOf("", "", ""), state.deck?.drafts)
    }

    @Test
    fun notificationRequest_opensDeck_whenTheClockOutIsReal() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        // claimNotificationRequest validates against the real attendance log.
        attendance.recordClockOut(locationId, clockOutAtMillis)
        val request = StatusUpdateRequest(locationId, clockOutAtMillis)

        viewModel.onNotificationRequest(request)
        runCurrent()

        assertEquals(0, viewModel.uiState.value.deck?.currentIndex)
    }

    @Test
    fun notificationRequest_isIgnored_whenTheClockOutIsForged() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        val forged = StatusUpdateRequest("no-such-site", clockOutAtMillis)

        viewModel.onNotificationRequest(forged)
        runCurrent()

        assertNull(viewModel.uiState.value.deck)
    }

    @Test
    fun dismissPrompt_clearsPromptWithoutOpeningDeck() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()

        viewModel.onDismissPrompt()
        runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.prompt)
        assertNull(state.deck)
    }

    @Test
    fun backingOutOfDeck_clearsWithoutSaving() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()
        viewModel.onBeginPrompt()
        viewModel.onDraftChanged(0, "Wrote the report")
        runCurrent()

        viewModel.onDismissDeck()
        runCurrent()

        assertNull(viewModel.uiState.value.deck)
        assertTrue(repository.statusUpdates.value.isEmpty())
    }

    @Test
    fun draftsPerCard_areRetainedAcrossIndexChanges() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()
        viewModel.onBeginPrompt()

        viewModel.onDraftChanged(0, "did today")
        viewModel.onForward()
        viewModel.onDraftChanged(1, "planned tomorrow")
        viewModel.onForward()
        viewModel.onDraftChanged(2, "could not do")
        viewModel.onBack()
        viewModel.onBack()
        runCurrent()

        val drafts = viewModel.uiState.value.deck?.drafts
        assertEquals(listOf("did today", "planned tomorrow", "could not do"), drafts)
        assertEquals(0, viewModel.uiState.value.deck?.currentIndex)
    }

    @Test
    fun index_clampsAtZero() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()
        viewModel.onBeginPrompt()

        viewModel.onBack()
        viewModel.onBack()
        runCurrent()

        assertEquals(0, viewModel.uiState.value.deck?.currentIndex)
    }

    @Test
    fun openDeck_ignoresASecondRequestWhileADeckIsAlreadyOpen() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()
        viewModel.onBeginPrompt()
        viewModel.onDraftChanged(0, "did today")
        runCurrent()

        // A second clock-out's notification tap arrives while the first deck is still open.
        attendance.recordClockOut("site-2", 2_000L)
        viewModel.onNotificationRequest(StatusUpdateRequest("site-2", 2_000L))
        runCurrent()

        assertEquals(listOf("did today", "", ""), viewModel.uiState.value.deck?.drafts)
    }

    @Test
    fun onBeginPrompt_whileADeckIsAlreadyOpen_leavesThePromptInPlace() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()
        viewModel.onBeginPrompt()
        viewModel.onDraftChanged(0, "did today")
        runCurrent()

        // A second clock-out's prompt arrives while the first deck is still open.
        coordinator.onClockOut("site-2", 2_000L, StatusUpdateTrigger.MANUAL)
        runCurrent()
        assertEquals(StatusUpdateRequest("site-2", 2_000L), viewModel.uiState.value.prompt)

        // Tapping Begin now must not accept (and thus discard) that prompt.
        viewModel.onBeginPrompt()
        runCurrent()

        assertEquals(StatusUpdateRequest("site-2", 2_000L), viewModel.uiState.value.prompt)
        assertEquals(listOf("did today", "", ""), viewModel.uiState.value.deck?.drafts)
    }

    @Test
    fun completion_savesOnceAndClosesDeck() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()
        viewModel.onBeginPrompt()

        viewModel.onDraftChanged(0, "did today")
        viewModel.onForward()
        viewModel.onDraftChanged(1, "planned tomorrow")
        viewModel.onForward()
        viewModel.onDraftChanged(2, "could not do")
        viewModel.onForward()
        runCurrent()

        assertNull(viewModel.uiState.value.deck)
        assertEquals(1, repository.statusUpdates.value.size)
        val saved = repository.statusUpdates.value.single()
        assertEquals("did today", saved.answers[StatusUpdateQuestion.DID_TODAY])
        assertEquals("planned tomorrow", saved.answers[StatusUpdateQuestion.PLANNED_TOMORROW])
        assertEquals("could not do", saved.answers[StatusUpdateQuestion.COULD_NOT_DO])
        assertEquals(5_000L, saved.completedAtMillis)

        // Submitting again without a new clock-out must not save a second time.
        viewModel.onForward()
        runCurrent()
        assertEquals(1, repository.statusUpdates.value.size)
    }

    @Test
    fun clockOutUndone_closesAMatchingOpenDeckWithoutSaving() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()
        viewModel.onBeginPrompt()
        viewModel.onDraftChanged(0, "did today")
        runCurrent()

        coordinator.onClockOutUndone(locationId, clockOutAtMillis)
        runCurrent()

        assertNull(viewModel.uiState.value.deck)
        assertTrue(repository.statusUpdates.value.isEmpty())
    }

    @Test
    fun clockOutUndone_forADifferentClockOut_leavesTheOpenDeckAlone() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        clockOut()
        runCurrent()
        viewModel.onBeginPrompt()
        viewModel.onDraftChanged(0, "did today")
        runCurrent()

        coordinator.onClockOutUndone("some-other-site", 9_999L)
        runCurrent()

        assertEquals("did today", viewModel.uiState.value.deck?.drafts?.get(0))
    }
}
