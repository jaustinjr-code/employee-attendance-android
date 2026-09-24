package com.jaustinjr.employeeattendance.statusupdate

import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.RecordingAttendanceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusUpdateCoordinatorTest {

    private fun answers(vararg drafts: String) = drafts.toList().toAnswers()

    private fun coordinator(
        enabled: Boolean = true,
        foreground: Boolean = false,
        notifier: RecordingStatusUpdateNotifier = RecordingStatusUpdateNotifier(),
        repository: StatusUpdateRepository = DefaultStatusUpdateRepository(),
        attendanceRepository: AttendanceRepository = RecordingAttendanceRepository(),
        nowMillis: Long = 5_000L,
        worksiteName: (String) -> String? = { null },
    ) = StatusUpdateCoordinator(
        enabled = MutableStateFlow(enabled),
        foregroundTracker = FakeAppForegroundTracker(foreground),
        notifier = notifier,
        repository = repository,
        attendanceRepository = attendanceRepository,
        clock = { nowMillis },
        worksiteName = worksiteName,
    )

    // --- disabled: no-op for every trigger ---

    @Test
    fun `disabled MANUAL clock-out does nothing`() {
        val notifier = RecordingStatusUpdateNotifier()
        val coord = coordinator(enabled = false, notifier = notifier)

        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.MANUAL)

        assertNull(coord.pendingPrompt.value)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `disabled AUTO clock-out does nothing`() {
        val notifier = RecordingStatusUpdateNotifier()
        val coord = coordinator(enabled = false, foreground = true, notifier = notifier)

        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.AUTO)

        assertNull(coord.pendingPrompt.value)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `disabled NOTIFICATION_CONFIRMED clock-out does nothing`() {
        val notifier = RecordingStatusUpdateNotifier()
        val coord = coordinator(enabled = false, notifier = notifier)

        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.NOTIFICATION_CONFIRMED)

        assertNull(coord.pendingPrompt.value)
        assertTrue(notifier.posted.isEmpty())
    }

    // --- enabled: trigger -> surface ---

    @Test
    fun `MANUAL clock-out shows the prompt`() {
        val coord = coordinator(foreground = false)

        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.MANUAL)

        assertEquals(StatusUpdateRequest("site-a", 1_000L), coord.pendingPrompt.value)
    }

    @Test
    fun `AUTO clock-out in the foreground shows the prompt`() {
        val coord = coordinator(foreground = true)

        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.AUTO)

        assertEquals(StatusUpdateRequest("site-a", 1_000L), coord.pendingPrompt.value)
    }

    @Test
    fun `AUTO clock-out in the background posts a notification`() {
        val notifier = RecordingStatusUpdateNotifier()
        val coord = coordinator(foreground = false, notifier = notifier)

        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.AUTO)

        assertNull(coord.pendingPrompt.value)
        assertEquals(listOf(StatusUpdateRequest("site-a", 1_000L)), notifier.posted)
    }

    @Test
    fun `NOTIFICATION_CONFIRMED clock-out always posts a notification, even in the foreground`() {
        val notifier = RecordingStatusUpdateNotifier()
        val coord = coordinator(foreground = true, notifier = notifier)

        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.NOTIFICATION_CONFIRMED)

        assertNull(coord.pendingPrompt.value)
        assertEquals(listOf(StatusUpdateRequest("site-a", 1_000L)), notifier.posted)
    }

    // --- prompt lifecycle ---

    @Test
    fun `acceptPrompt clears the prompt and returns the request`() {
        val coord = coordinator()
        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.MANUAL)

        val accepted = coord.acceptPrompt()

        assertEquals(StatusUpdateRequest("site-a", 1_000L), accepted)
        assertNull(coord.pendingPrompt.value)
    }

    @Test
    fun `acceptPrompt with nothing pending returns null`() {
        val coord = coordinator()

        assertNull(coord.acceptPrompt())
    }

    @Test
    fun `dismissPrompt clears the prompt`() {
        val coord = coordinator()
        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.MANUAL)

        coord.dismissPrompt()

        assertNull(coord.pendingPrompt.value)
    }

    // --- claimNotificationRequest: MainActivity is exported, so extras are untrusted ---

    @Test
    fun `claimNotificationRequest returns the request when the clock-out is real and unfinished`() {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockOut("site-a", 1_000L)
        val coord = coordinator(attendanceRepository = attendance)

        val claimed = coord.claimNotificationRequest(StatusUpdateRequest("site-a", 1_000L))

        assertEquals(StatusUpdateRequest("site-a", 1_000L), claimed)
    }

    @Test
    fun `claimNotificationRequest rejects a request with no matching clock-out event`() {
        val coord = coordinator(attendanceRepository = RecordingAttendanceRepository())

        val claimed = coord.claimNotificationRequest(StatusUpdateRequest("site-a", 1_000L))

        assertNull(claimed)
    }

    @Test
    fun `claimNotificationRequest rejects a request already completed`() {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockOut("site-a", 1_000L)
        val repository = DefaultStatusUpdateRepository()
        val coord = coordinator(attendanceRepository = attendance, repository = repository)
        val request = StatusUpdateRequest("site-a", 1_000L)
        coord.complete(request, answers("a", "b", "c"))

        val claimed = coord.claimNotificationRequest(request)

        assertNull(claimed)
    }

    // --- onClockOutUndone ---

    @Test
    fun `onClockOutUndone clears a matching pending prompt and cancels the notification`() = runTest {
        val notifier = RecordingStatusUpdateNotifier()
        val coord = coordinator(foreground = true, notifier = notifier)
        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.MANUAL)

        coord.onClockOutUndone("site-a", 1_000L)

        assertNull(coord.pendingPrompt.value)
        assertEquals(listOf(StatusUpdateRequest("site-a", 1_000L)), notifier.cancelled)
    }

    @Test
    fun `onClockOutUndone emits the clockOutId on undoneClockOuts`() = runTest {
        val coord = coordinator()
        val received = mutableListOf<String>()
        backgroundScope.launch { coord.undoneClockOuts.collect { received += it } }
        runCurrent()

        coord.onClockOutUndone("site-a", 1_000L)
        runCurrent()

        assertEquals(listOf("site-a@1000"), received)
    }

    @Test
    fun `onClockOutUndone for a different clock-out leaves the pending prompt alone`() {
        val coord = coordinator(foreground = true)
        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.MANUAL)

        coord.onClockOutUndone("site-b", 2_000L)

        assertEquals(StatusUpdateRequest("site-a", 1_000L), coord.pendingPrompt.value)
    }

    // --- complete ---

    @Test
    fun `complete saves the status update via the repository with the injected clock`() {
        val repository = DefaultStatusUpdateRepository()
        val coord = coordinator(repository = repository, nowMillis = 9_999L)
        val request = StatusUpdateRequest("site-a", 1_000L)

        coord.complete(request, answers("did today", "planned tomorrow", "could not do"))

        val saved = repository.statusUpdates.value.single()
        assertEquals("site-a@1000", saved.clockOutId)
        assertEquals("did today", saved.answers[StatusUpdateQuestion.DID_TODAY])
        assertEquals("planned tomorrow", saved.answers[StatusUpdateQuestion.PLANNED_TOMORROW])
        assertEquals("could not do", saved.answers[StatusUpdateQuestion.COULD_NOT_DO])
        assertEquals(9_999L, saved.completedAtMillis)
    }

    @Test
    fun `a second AUTO clock-out replaces a still-pending prompt`() {
        val coord = coordinator(foreground = true)
        coord.onClockOut("site-a", 1_000L, StatusUpdateTrigger.AUTO)

        coord.onClockOut("site-b", 2_000L, StatusUpdateTrigger.AUTO)

        assertEquals(StatusUpdateRequest("site-b", 2_000L), coord.pendingPrompt.value)
        assertFalse(coord.pendingPrompt.value?.clockOutId == "site-a@1000")
    }

    @Test
    fun `complete with every answer blank saves nothing`() {
        val repository = DefaultStatusUpdateRepository()
        val coord = coordinator(repository = repository)

        coord.complete(StatusUpdateRequest("site-a", 1_000L), answers("", "  ", "\n"))

        assertTrue(repository.statusUpdates.value.isEmpty())
    }

    @Test
    fun `complete with one answer filled saves it`() {
        val repository = DefaultStatusUpdateRepository()
        val coord = coordinator(repository = repository)

        coord.complete(StatusUpdateRequest("site-a", 1_000L), answers("", "", "Van was in the shop"))

        assertEquals("Van was in the shop", repository.statusUpdates.value.single().answers[StatusUpdateQuestion.COULD_NOT_DO])
    }

    @Test
    fun `complete records the shift's clock-in, clock-out and worksite name`() {
        val attendance = RecordingAttendanceRepository()
        attendance.recordClockIn("site-a", 400L)
        attendance.recordClockOut("site-a", 1_000L)
        val repository = DefaultStatusUpdateRepository()
        val coord = coordinator(
            repository = repository,
            attendanceRepository = attendance,
            worksiteName = { if (it == "site-a") "Main office" else null },
        )

        coord.complete(StatusUpdateRequest("site-a", 1_000L), answers("did", "", ""))

        val saved = repository.statusUpdates.value.single()
        assertEquals(400L, saved.clockInAtMillis)
        assertEquals(1_000L, saved.clockOutAtMillis)
        assertEquals("Main office", saved.worksiteName)
        assertNull(saved.editedAtMillis)
    }
}
