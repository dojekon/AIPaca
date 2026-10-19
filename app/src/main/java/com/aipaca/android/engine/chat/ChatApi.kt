package com.aipaca.android.engine.chat

data class ApiMessage(
    val role: String,
    val content: String,
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
)

fun buildApiMessages(request: ChatRequest): List<ApiMessage> =
    if (request.systemPrompt != null) {
        listOf(ApiMessage(role = "system", content = request.systemPrompt)) + request.messages
    } else {
        request.messages
    }
