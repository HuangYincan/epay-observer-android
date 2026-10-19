package dev.epay.observer.sample

import org.junit.Assert.*
import org.junit.Test

class ComparisonBudgetTest {
    @Test fun slotsAreIndependentAndFailuresCannotRetry() {
        val budget = ComparisonBudget(1000)
        assertTrue(budget.claim(false, 1000))
        assertFalse(budget.claim(false, 1001))
        assertTrue(budget.claim(true, 1002))
        assertFalse(budget.claim(true, 1003))
    }
    @Test fun deadlineAndStopCloseBeforeRead() {
        val budget = ComparisonBudget(1000)
        assertFalse(budget.claim(true, 999))
        assertTrue(budget.running(120999))
        assertFalse(budget.claim(true, 121000))
        val stopped = ComparisonBudget(0)
        stopped.stop()
        assertFalse(stopped.claim(false, 1))
    }
}
