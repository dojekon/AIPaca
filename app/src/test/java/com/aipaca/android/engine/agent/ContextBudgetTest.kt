package com.aipaca.android.engine.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun estimateTokensEmptyIsZero() {
        assertEquals(0, estimateTokens(""))
    }

    @Test
    fun estimateTokensRoundsUpQuarters() {
        assertEquals(1, estimateTokens("abcd"))
        assertEquals(2, estimateTokens("abcde"))
    }

    @Test
    fun estimateTokensCountsUnicodeChars() {
        assertEquals(2, estimateTokens("привет"))
        assertEquals(1, estimateTokens("日本語"))
    }

    @Test
    fun contextBudgetWithoutToolsUsesReserve() {
        assertEquals(4096 - CONTEXT_RESERVE_TOKENS, contextBudget(4096))
    }

    @Test
    fun toolsCharsReduceBudget() {
        assertEquals(4096 - CONTEXT_RESERVE_TOKENS - 100, contextBudget(4096, 400))
    }

    @Test
    fun tinyContextClampsToMinimum() {
        assertEquals(MIN_CONTEXT_BUDGET, contextBudget(600))
    }

    @Test
    fun negativeContextClampsToMinimum() {
        assertEquals(MIN_CONTEXT_BUDGET, contextBudget(-1))
        assertEquals(MIN_CONTEXT_BUDGET, contextBudget(0))
    }

    @Test
    fun toolsLargerThanContextClampsToMinimum() {
        assertEquals(MIN_CONTEXT_BUDGET, contextBudget(4096, 100_000))
    }

    @Test
    fun toolOutputLimitScalesWithBudgetWithinBounds() {
        assertEquals(2864, toolOutputCharLimit(2728))
        assertEquals(1024, toolOutputCharLimit(100))
        assertEquals(TOOL_OUTPUT_MAX_CHARS, toolOutputCharLimit(100_000))
        assertEquals(2564, fetchPageCharLimit(2864))
    }
}
