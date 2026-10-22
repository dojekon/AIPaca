package com.aipaca.android.engine.chat

interface ChatStreamer {
    suspend fun stream(
        serverUrl: String,
        apiKey: String?,
        request: ChatRequest,
        onToolCall: (ChatEvent.ToolCallDelta) -> Unit = {},
        onDelta: (String, Boolean) -> Unit,
    ): Result<ChatStreamResult>
}
