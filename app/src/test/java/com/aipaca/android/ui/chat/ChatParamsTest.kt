package com.aipaca.android.ui.chat

import com.aipaca.android.data.db.entity.ChatMode
import org.junit.Assert.assertEquals
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
}
