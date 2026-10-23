package com.aipaca.android.engine.agent

const val CONTEXT_RESERVE_TOKENS = 768
const val MIN_CONTEXT_BUDGET = 512

fun estimateTokens(text: String): Int =
    if (text.isEmpty()) 0 else (text.length + 3) / 4

fun contextBudget(nCtx: Int, toolsChars: Int = 0): Int {
    if (nCtx <= 0) return MIN_CONTEXT_BUDGET
    val toolsTokens = if (toolsChars <= 0) 0 else (toolsChars + 3) / 4
    return (nCtx - CONTEXT_RESERVE_TOKENS - toolsTokens).coerceAtLeast(MIN_CONTEXT_BUDGET)
}

private const val TOOL_OUTPUT_BUDGET_SHARE = 0.35
private const val TOOL_OUTPUT_CHARS_PER_TOKEN = 3
private const val TOOL_OUTPUT_MIN_CHARS = 1024
private const val FETCH_PAGE_HEADER_RESERVE = 300

fun toolOutputCharLimit(budgetTokens: Int): Int =
    (budgetTokens * TOOL_OUTPUT_BUDGET_SHARE * TOOL_OUTPUT_CHARS_PER_TOKEN).toInt()
        .coerceIn(TOOL_OUTPUT_MIN_CHARS, TOOL_OUTPUT_MAX_CHARS)

fun fetchPageCharLimit(toolOutputChars: Int): Int =
    (toolOutputChars - FETCH_PAGE_HEADER_RESERVE).coerceAtLeast(TOOL_OUTPUT_MIN_CHARS / 2)
