package com.aipaca.android.ui.chat

import com.aipaca.android.data.db.entity.ChatMode

fun defaultChatParams(mode: String): ChatViewModel.ChatParamsUi =
    if (mode == ChatMode.AGENT) {
        ChatViewModel.ChatParamsUi(
            temperature = 0.6,
            topP = 0.95,
            topK = 20,
            minP = 0.0,
            repeatPenalty = 1.0,
        )
    } else {
        ChatViewModel.ChatParamsUi()
    }
