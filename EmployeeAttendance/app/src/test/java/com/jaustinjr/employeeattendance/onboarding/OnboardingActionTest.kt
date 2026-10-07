package com.jaustinjr.employeeattendance.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingActionTest {

    @Test
    fun `every page but the last advances`() {
        assertEquals(OnboardingAction.NEXT, onboardingActionFor(currentPage = 0, pageCount = 3))
        assertEquals(OnboardingAction.NEXT, onboardingActionFor(currentPage = 1, pageCount = 3))
    }

    @Test
    fun `the last page finishes`() {
        assertEquals(OnboardingAction.FINISH, onboardingActionFor(currentPage = 2, pageCount = 3))
    }

    @Test
    fun `a single page finishes immediately`() {
        assertEquals(OnboardingAction.FINISH, onboardingActionFor(currentPage = 0, pageCount = 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a page past the end is rejected`() {
        onboardingActionFor(currentPage = 3, pageCount = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty carousel is rejected`() {
        onboardingActionFor(currentPage = 0, pageCount = 0)
    }

    @Test
    fun `the carousel covers attendance, worksites and reporting in that order`() {
        assertEquals(
            listOf(OnboardingPage.ATTENDANCE, OnboardingPage.WORKSITE, OnboardingPage.REPORTING),
            OnboardingPage.entries,
        )
    }
}
