package com.aipaca.android.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatApiTest {

    @Test
    fun systemPromptIsInsertedFirst() {
        val request = ChatRequest(
            messages = listOf(
                ApiMessage("user", "first"),
                ApiMessage("assistant", "second"),
                ApiMessage("user", "third"),
            ),
            systemPrompt = "You are a helpful assistant",
        )

        val messages = buildApiMessages(request)

        assertEquals(
            listOf(
                ApiMessage("system", "You are a helpful assistant"),
                ApiMessage("user", "first"),
                ApiMessage("assistant", "second"),
                ApiMessage("user", "third"),
            ),
            messages,
        )
    }

    @Test
    fun withoutSystemPromptOrderIsPreserved() {
        val request = ChatRequest(
            messages = listOf(
                ApiMessage("user", "hello"),
                ApiMessage("assistant", "hi"),
            ),
            systemPrompt = null,
        )

        val messages = buildApiMessages(request)

        assertEquals(
            listOf(
                ApiMessage("user", "hello"),
                ApiMessage("assistant", "hi"),
            ),
            messages,
        )
    }

    @Test
    fun defaultsMatchSamplingDoc() {
        val request = ChatRequest(messages = emptyList(), systemPrompt = null)

        assertEquals(0.7, request.temperature, 0.0)
        assertEquals(0.95, request.topP, 0.0)
        assertEquals(40, request.topK)
        assertEquals(0.05, request.minP, 0.0)
        assertEquals(1.1, request.repeatPenalty, 0.0)
    }

    @Test
    fun maxTokensDefaultsToNull() {
        val request = ChatRequest(messages = emptyList(), systemPrompt = null)

        assertNull(request.maxTokens)
    }
}
