package com.aipaca.android.engine.agent

import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ApiToolCall

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
