package com.aipaca.android.engine.agent

import com.aipaca.android.engine.chat.ApiMessage

const val DROPPED_CONTEXT_MARKER = "[dropped from context]"
const val TRIM_TARGET_RATIO = 0.7

private const val MESSAGE_OVERHEAD_TOKENS = 16

data class TrimResult(
    val messages: List<ApiMessage>,
    val droppedToolResults: Int,
    val droppedMessages: Int,
    val estimatedTokens: Int,
    val trimmed: Boolean,
)

fun estimateMessageTokens(message: ApiMessage): Int {
    val toolCallArguments = message.toolCalls.orEmpty().joinToString(separator = "") { it.argumentsJson }
    val text = message.role + message.content + (message.toolCallId ?: "") + toolCallArguments
    return estimateTokens(text) + MESSAGE_OVERHEAD_TOKENS
}

fun trimHistory(
    messages: List<ApiMessage>,
    budgetTokens: Int,
    keepLastExchanges: Int = 3,
): TrimResult {
    val total = messages.sumOf { estimateMessageTokens(it) }
    if (total <= budgetTokens) {
        return TrimResult(
            messages = messages,
            droppedToolResults = 0,
            droppedMessages = 0,
            estimatedTokens = total,
            trimmed = false,
        )
    }

    val kept = messages.toMutableList()
    var current = total
    var droppedToolResults = 0

    for (index in kept.indices) {
        if (current <= budgetTokens) break
        val message = kept[index]
        if (message.role == "tool" && message.content != DROPPED_CONTEXT_MARKER) {
            val before = estimateMessageTokens(message)
            val replaced = message.copy(content = DROPPED_CONTEXT_MARKER)
            val after = estimateMessageTokens(replaced)
            kept[index] = replaced
            current += after - before
            droppedToolResults++
        }
    }

    var droppedMessages = 0

    if (current > budgetTokens) {
        val userIndices = kept.indices.filter { kept[it].role == "user" }
        val lastExchanges = keepLastExchanges.coerceAtLeast(1)

        if (userIndices.isEmpty()) {
            val keepNonSystem = lastExchanges * 2
            val nonSystemIndices = kept.indices.filter { kept[it].role != "system" }
            val keepIndices = nonSystemIndices.takeLast(keepNonSystem).toSet()
            val filtered = kept.filterIndexed { index, message ->
                message.role == "system" || index in keepIndices
            }
            droppedMessages = kept.size - filtered.size
            kept.clear()
            kept.addAll(filtered)
        } else {
            val remove = BooleanArray(kept.size)
            val lastDroppableTurn = userIndices.size - lastExchanges
            for (turn in 1 until lastDroppableTurn) {
                if (current <= budgetTokens) break
                val start = userIndices[turn]
                val end = userIndices[turn + 1]
                for (index in start until end) {
                    if (remove[index] || kept[index].role == "system") continue
                    remove[index] = true
                    current -= estimateMessageTokens(kept[index])
                    droppedMessages++
                }
            }
            if (remove.any { it }) {
                val filtered = kept.filterIndexed { index, _ -> !remove[index] }
                kept.clear()
                kept.addAll(filtered)
            }
        }
    }

    val result = kept.toList()
    return TrimResult(
        messages = result,
        droppedToolResults = droppedToolResults,
        droppedMessages = droppedMessages,
        estimatedTokens = result.sumOf { estimateMessageTokens(it) },
        trimmed = result != messages,
    )
}

fun trimWithHysteresis(
    messages: List<ApiMessage>,
    budgetTokens: Int,
    keepLastExchanges: Int = 3,
    targetRatio: Double = TRIM_TARGET_RATIO,
): TrimResult {
    val total = messages.sumOf { estimateMessageTokens(it) }
    if (total <= budgetTokens) {
        return TrimResult(messages, droppedToolResults = 0, droppedMessages = 0, estimatedTokens = total, trimmed = false)
    }
    val target = (budgetTokens * targetRatio).toInt().coerceAtLeast(1)
    return trimHistory(messages, target, keepLastExchanges)
}
