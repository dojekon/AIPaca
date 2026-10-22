package com.aipaca.android.engine.agent

import com.aipaca.android.data.db.entity.MessageEntity
import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ApiToolCall

fun toApiMessages(messages: List<MessageEntity>): List<ApiMessage> =
    messages.mapNotNull { message ->
        when (message.role) {
            "user" -> ApiMessage(role = "user", content = message.content)
            "assistant" -> {
                val toolCalls = decodeToolCalls(message.toolCalls)
                if (toolCalls.isEmpty()) {
                    ApiMessage(role = "assistant", content = message.content)
                } else {
                    ApiMessage(
                        role = "assistant",
                        content = message.content,
                        toolCalls = toolCalls.map { ApiToolCall(it.id, it.name, it.argsJson) },
                    )
                }
            }
            "tool" -> {
                val toolCallId = message.toolCallId
                if (toolCallId.isNullOrBlank()) {
                    null
                } else {
                    ApiMessage(role = "tool", content = message.content, toolCallId = toolCallId)
                }
            }
            "system" -> ApiMessage(role = "system", content = message.content)
            else -> ApiMessage(role = message.role, content = message.content)
        }
    }
