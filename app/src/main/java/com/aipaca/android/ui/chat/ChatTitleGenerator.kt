package com.aipaca.android.ui.chat

import android.util.Log
import com.aipaca.android.AppContainer
import com.aipaca.android.engine.agent.decodeToolCalls
import com.aipaca.android.engine.chat.ChatClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class ChatTitleGenerator(
    container: AppContainer,
    private val chatClient: ChatClient = ChatClient(),
) {
    private val chatDao = container.database.chatDao()
    private val messageDao = container.database.messageDao()
    private val engineManager = container.engineManager
    private val settingsRepository = container.settingsRepository

    suspend fun maybeGenerate(chatId: Long) {
        try {
            val serverUrl = engineManager.serverUrl ?: return
            val history = messageDao.getForChat(chatId)
            val conversational = history.filter {
                it.role == ChatViewModel.ROLE_USER ||
                    (
                        it.role == ChatViewModel.ROLE_ASSISTANT &&
                            it.content.isNotBlank() &&
                            decodeToolCalls(it.toolCalls).isEmpty()
                        )
            }
            val userMessage = conversational.firstOrNull { it.role == ChatViewModel.ROLE_USER } ?: return
            val assistantMessage = conversational.firstOrNull { it.role == ChatViewModel.ROLE_ASSISTANT } ?: return
            val chat = chatDao.getById(chatId) ?: return
            val autoTitle = chatTitleFrom(userMessage.content)
            if (!shouldGenerateTitle(conversational.size, chat.title, autoTitle)) return
            val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
            val generated = chatClient.complete(
                serverUrl = serverUrl,
                apiKey = apiKey,
                request = buildTitleRequest(userMessage.content, assistantMessage.content),
                timeoutSeconds = TITLE_TIMEOUT_SECONDS,
            ).getOrElse { error ->
                Log.w(TAG, "Title request failed", error)
                return
            }
            val title = sanitizeGeneratedTitle(generated)
            if (title == null) {
                Log.w(TAG, "Title response rejected: ${generated.take(80)}")
                return
            }
            chatDao.updateTitleIfMatches(chatId, title, autoTitle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Title generation failed", e)
        }
    }

    private companion object {
        const val TAG = "ChatTitle"
        const val TITLE_TIMEOUT_SECONDS = 30L
    }
}
