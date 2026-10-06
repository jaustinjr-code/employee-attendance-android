package com.jaustinjr.employeeattendance.onboarding.ui

import com.jaustinjr.employeeattendance.onboarding.OnboardingStore
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakeOnboardingStore(completed: Boolean) : OnboardingStore {
        private val _completed = MutableStateFlow(completed)
        override val completed: StateFlow<Boolean> = _completed
        override val resetPending: StateFlow<Boolean> = MutableStateFlow(false)
        var markCompletedCalls = 0

        override fun markCompleted() {
            markCompletedCalls++
            _completed.value = true
        }

        override fun resetForNextLaunch() = Unit
    }

    @Test
    fun `a first launch shows onboarding`() {
        val viewModel = OnboardingViewModel(FakeOnboardingStore(completed = false))
        assertTrue(viewModel.showOnboarding.value)
    }

    @Test
    fun `a returning user skips onboarding without a collector`() {
        // The initial value must already be right before anything subscribes: the gate reads it
        // on its first frame, and a wrong seed would flash the carousel at a returning user.
        val viewModel = OnboardingViewModel(FakeOnboardingStore(completed = true))
        assertFalse(viewModel.showOnboarding.value)
    }

    @Test
    fun `completing records it in the store and hides onboarding`() = runTest {
        val store = FakeOnboardingStore(completed = false)
        val viewModel = OnboardingViewModel(store)
        val collector = launch(mainRule.dispatcher) { viewModel.showOnboarding.collect {} }

        viewModel.complete()

        assertEquals(1, store.markCompletedCalls)
        assertTrue(store.completed.value)
        assertFalse(viewModel.showOnboarding.value)
        collector.cancel()
    }
}
