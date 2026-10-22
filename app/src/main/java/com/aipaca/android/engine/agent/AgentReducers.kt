package com.aipaca.android.engine.agent

import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ApiToolCall
import kotlinx.serialization.json.Json

fun stepsGuarded(iteration: Int, maxIterations: Int): Boolean = iteration < maxIterations

fun shouldContinue(
    toolCalls: List<AgentToolCall>,
    iteration: Int,
    maxIterations: Int,
): Boolean = toolCalls.isNotEmpty() && stepsGuarded(iteration, maxIterations)

fun assistantApiMessage(content: String, toolCalls: List<AgentToolCall>): ApiMessage =
    ApiMessage(
        role = "assistant",
        content = content,
        toolCalls = if (toolCalls.isEmpty()) {
            null
        } else {
            toolCalls.map { ApiToolCall(it.id, it.name, it.argsJson) }
        },
    )

fun toolApiMessage(call: AgentToolCall, result: ToolResult): ApiMessage =
    ApiMessage(
        role = "tool",
        content = encodeToolResult(result),
        toolCallId = call.id,
    )

const val REPEATED_CALL_LIMIT = 3
const val REPEATED_CALL_MESSAGE =
    "Identical tool call repeated $REPEATED_CALL_LIMIT times in a row, so it was not executed. " +
        "Use a different approach or write the final answer."
const val MAX_CONTEXT_OVERFLOW_RETRIES = 2

private val signatureJson = Json
private val CONTEXT_OVERFLOW_REGEX =
    Regex("""request \((\d+) tokens\) exceeds the available context size \((\d+) tokens\)""")
private const val CONTEXT_OVERFLOW_TYPE = "exceed_context_size_error"

fun toolCallSignature(call: AgentToolCall): String {
    val args = try {
        signatureJson.parseToJsonElement(call.argsJson).toString()
    } catch (_: Exception) {
        call.argsJson.trim()
    }
    return "${call.name}:$args"
}

fun isRepeatedCall(previous: List<String>, signature: String, limit: Int = REPEATED_CALL_LIMIT): Boolean {
    val needed = limit - 1
    return needed <= 0 || (previous.size >= needed && previous.takeLast(needed).all { it == signature })
}

data class ContextOverflow(val requestedTokens: Int?, val contextTokens: Int?)

fun parseContextOverflow(message: String?): ContextOverflow? {
    if (message.isNullOrBlank()) return null
    CONTEXT_OVERFLOW_REGEX.find(message)?.let { match ->
        return ContextOverflow(
            requestedTokens = match.groupValues[1].toIntOrNull(),
            contextTokens = match.groupValues[2].toIntOrNull(),
        )
    }
    return if (message.contains(CONTEXT_OVERFLOW_TYPE)) ContextOverflow(null, null) else null
}

fun shrunkBudgetScale(current: Double, overflow: ContextOverflow): Double {
    val requested = overflow.requestedTokens
    val context = overflow.contextTokens
    val ratio = if (requested != null && context != null && requested > 0) {
        context.toDouble() / requested
    } else {
        FALLBACK_SHRINK
    }
    return (current * ratio * SHRINK_MARGIN).coerceIn(MIN_BUDGET_SCALE, current)
}

private const val FALLBACK_SHRINK = 0.7
private const val SHRINK_MARGIN = 0.85
private const val MIN_BUDGET_SCALE = 0.1
