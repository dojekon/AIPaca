package com.aipaca.android.ui.chat

import com.aipaca.android.data.db.entity.ChatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatParamsTest {

    @Test
    fun chatModeKeepsDefaults() {
        assertEquals(ChatViewModel.ChatParamsUi(), defaultChatParams(ChatMode.CHAT))
    }

    @Test
    fun agentModeDisablesRepeatPenaltyAndLowersTemperature() {
        val params = defaultChatParams(ChatMode.AGENT)

        assertEquals(1.0, params.repeatPenalty, 0.0)
        assertEquals(0.6, params.temperature, 0.0)
        assertEquals(20, params.topK)
        assertEquals(0.0, params.minP, 0.0)
    }

    @Test
    fun defaultParamsAreNotStored() {
        assertNull(encodeChatParams(defaultChatParams(ChatMode.CHAT), ChatMode.CHAT))
        assertNull(encodeChatParams(defaultChatParams(ChatMode.AGENT), ChatMode.AGENT))
    }

    @Test
    fun customParamsRoundTrip() {
        val params = defaultChatParams(ChatMode.AGENT).copy(temperature = 0.3, topK = 50, maxIterations = 12)

        val json = encodeChatParams(params, ChatMode.AGENT)

        assertEquals(params, decodeChatParams(json, ChatMode.AGENT))
    }

    @Test
    fun missingOrBrokenJsonFallsBackToModeDefaults() {
        assertEquals(defaultChatParams(ChatMode.AGENT), decodeChatParams(null, ChatMode.AGENT))
        assertEquals(defaultChatParams(ChatMode.CHAT), decodeChatParams("{oops", ChatMode.CHAT))
        assertEquals(
            defaultChatParams(ChatMode.CHAT).copy(temperature = 0.1),
            decodeChatParams("""{"temperature":0.1,"topK":"x"}""", ChatMode.CHAT),
        )
    }
}
