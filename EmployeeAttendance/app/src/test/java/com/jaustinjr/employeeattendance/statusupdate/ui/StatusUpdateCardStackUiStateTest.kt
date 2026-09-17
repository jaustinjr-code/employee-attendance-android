package com.jaustinjr.employeeattendance.statusupdate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusUpdateCardStackUiStateTest {

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongNumberOfDrafts() {
        StatusUpdateCardStackUiState(drafts = listOf("", ""), currentIndex = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeIndex() {
        StatusUpdateCardStackUiState(drafts = listOf("", "", ""), currentIndex = 3)
    }

    @Test
    fun isFirstAndIsLastCard_derivedFromIndex() {
        val first = StatusUpdateCardStackUiState(drafts = listOf("", "", ""), currentIndex = 0)
        assertTrue(first.isFirstCard)
        assertFalse(first.isLastCard)

        val last = StatusUpdateCardStackUiState(drafts = listOf("", "", ""), currentIndex = 2)
        assertFalse(last.isFirstCard)
        assertTrue(last.isLastCard)
    }
}
