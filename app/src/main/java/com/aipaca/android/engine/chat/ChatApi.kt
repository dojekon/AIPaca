package com.aipaca.android.engine.chat

data class ToolDef(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

data class ApiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class AssembledToolCall(
    val index: Int,
    val id: String?,
    val name: String?,
    val argumentsJson: String,
)

data class ApiMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ApiToolCall>? = null,
)

data class ChatRequest(
    val messages: List<ApiMessage>,
    val systemPrompt: String?,
    val temperature: Double = 0.7,
    val topP: Double = 0.95,
    val topK: Int = 40,
    val minP: Double = 0.05,
    val repeatPenalty: Double = 1.1,
    val maxTokens: Int? = null,
    val tools: List<ToolDef> = emptyList(),
    val toolChoice: String? = null,
    val enableThinking: Boolean? = null,
    val reasoningBudget: Int? = null,
    val reasoningBudgetMessage: String? = null,
)

data class ChatTimings(
    val predictedPerSecond: Double?,
    val promptPerSecond: Double?,
    val predictedN: Int?,
    val promptN: Int?,
)

data class ChatStreamResult(
    val content: String,
    val timings: ChatTimings?,
    val toolCalls: List<AssembledToolCall> = emptyList(),
)

fun buildApiMessages(request: ChatRequest): List<ApiMessage> =
    if (request.systemPrompt != null) {
        listOf(ApiMessage(role = "system", content = request.systemPrompt)) + request.messages
    } else {
        request.messages
    }
