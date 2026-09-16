package com.jaustinjr.employeeattendance.statusupdate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [AppForegroundTracker] test double with a directly settable [isForeground]. */
class FakeAppForegroundTracker(initial: Boolean = false) : AppForegroundTracker {
    private val _isForeground = MutableStateFlow(initial)
    override val isForeground: StateFlow<Boolean> = _isForeground

    fun setForeground(value: Boolean) {
        _isForeground.value = value
    }
}

/** [StatusUpdateNotifications] test double recording every posted/cancelled request. */
class RecordingStatusUpdateNotifier : StatusUpdateNotifications {
    val posted = mutableListOf<StatusUpdateRequest>()
    val cancelled = mutableListOf<StatusUpdateRequest>()

    override fun notifyPending(request: StatusUpdateRequest) {
        posted += request
    }

    override fun cancel(request: StatusUpdateRequest) {
        cancelled += request
    }
}
