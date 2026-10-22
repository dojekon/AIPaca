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
