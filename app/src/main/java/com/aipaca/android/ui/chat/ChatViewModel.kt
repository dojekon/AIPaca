package com.aipaca.android.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aipaca.android.AiPacaApp
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.ChatEntity
import com.aipaca.android.data.db.entity.MessageEntity
import com.aipaca.android.engine.EngineState
import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ChatClient
import com.aipaca.android.engine.chat.ChatRequest
import com.aipaca.android.engine.chat.ChatTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    chatId: Long,
    private val application: Application,
    container: AppContainer,
) : ViewModel() {

    data class MessageUi(
        val id: Long,
        val role: String,
        val content: String,
        val reasoning: String?,
        val tokensIn: Int?,
        val tokensOut: Int?,
        val tps: Double?,
    )

    data class ChatParamsUi(
        val temperature: Double = 0.7,
        val topP: Double = 0.95,
        val topK: Int = 40,
        val minP: Double = 0.05,
        val repeatPenalty: Double = 1.1,
    )

    private val chatDao = container.database.chatDao()
    private val messageDao = container.database.messageDao()
    private val engineManager = container.engineManager
    private val settingsRepository = container.settingsRepository
    private val chatClient = ChatClient()

    private val currentChatId = MutableStateFlow(chatId)
    private var streamJob: Job? = null

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _streamingReasoning = MutableStateFlow<String?>(null)
    val streamingReasoning: StateFlow<String?> = _streamingReasoning.asStateFlow()

    private val _streamingMessageId = MutableStateFlow<Long?>(null)
    val streamingMessageId: StateFlow<Long?> = _streamingMessageId.asStateFlow()

    private val _lastTimings = MutableStateFlow<ChatTimings?>(null)
    val lastTimings: StateFlow<ChatTimings?> = _lastTimings.asStateFlow()

    private val _params = MutableStateFlow(ChatParamsUi())
    val params: StateFlow<ChatParamsUi> = _params.asStateFlow()

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors: Flow<String> = _errors.receiveAsFlow()

    val messages: StateFlow<List<MessageUi>> = currentChatId
        .flatMapLatest { id ->
            if (id == 0L) {
                flowOf(emptyList())
            } else {
                messageDao.observeForChat(id).map { entities -> entities.map { it.toUi() } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    val title: StateFlow<String?> = currentChatId
        .flatMapLatest { id ->
            if (id == 0L) flowOf(null) else chatDao.observeById(id).map { it?.title }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    val modelName: StateFlow<String?> = engineManager.state
        .map { (it as? EngineState.Ready)?.modelPath?.substringAfterLast('/')?.substringBeforeLast('.') }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    fun send(text: String): Boolean {
        val content = text.trim()
        if (content.isEmpty() || streamJob?.isActive == true) return false
        val serverUrl = readyServerUrl() ?: return false
        streamJob = viewModelScope.launch {
            val chat = ensureChat(content)
            messageDao.insert(
                MessageEntity(
                    chatId = chat,
                    role = ROLE_USER,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            stream(chat, serverUrl)
        }
        return true
    }

    fun stop() {
        streamJob?.cancel()
    }

    fun renameChat(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val id = currentChatId.value
        if (id == 0L) return
        viewModelScope.launch {
            chatDao.updateTitle(id, trimmed)
            chatDao.updateTimestamp(id, System.currentTimeMillis())
        }
    }

    fun deleteChat() {
        val id = currentChatId.value
        if (id == 0L) return
        streamJob?.cancel()
        viewModelScope.launch {
            withContext(NonCancellable) {
                val chat = chatDao.observeById(id).first() ?: return@withContext
                messageDao.deleteForChat(id)
                chatDao.delete(chat)
                currentChatId.value = 0L
            }
        }
    }

    fun regenerate(): Boolean {
        if (streamJob?.isActive == true) return false
        val serverUrl = readyServerUrl() ?: return false
        val chat = currentChatId.value
        if (chat == 0L) return false
        streamJob = viewModelScope.launch {
            val history = messageDao.getForChat(chat)
            val lastAssistant = history.lastOrNull { it.role == ROLE_ASSISTANT } ?: return@launch
            messageDao.delete(lastAssistant)
            stream(chat, serverUrl)
        }
        return true
    }

    fun updateTemperature(value: Double) {
        _params.update { it.copy(temperature = value) }
    }

    fun updateTopP(value: Double) {
        _params.update { it.copy(topP = value) }
    }

    fun updateTopK(value: Int) {
        _params.update { it.copy(topK = value) }
    }

    fun updateMinP(value: Double) {
        _params.update { it.copy(minP = value) }
    }

    fun updateRepeatPenalty(value: Double) {
        _params.update { it.copy(repeatPenalty = value) }
    }

    fun updateSystemPrompt(value: String) {
        _systemPrompt.value = value
    }

    fun resetParams() {
        _params.value = ChatParamsUi()
    }

    private fun readyServerUrl(): String? {
        val url = engineManager.serverUrl
        if (url == null) {
            _errors.trySend(application.getString(R.string.chat_error_engine_off))
        }
        return url
    }

    private suspend fun ensureChat(firstMessage: String): Long {
        val existing = currentChatId.value
        if (existing != 0L) return existing
        val now = System.currentTimeMillis()
        val id = chatDao.insert(
            ChatEntity(
                title = chatTitleFrom(firstMessage),
                createdAt = now,
                updatedAt = now,
            ),
        )
        currentChatId.value = id
        return id
    }

    private suspend fun stream(chatId: Long, serverUrl: String) {
        val placeholder = MessageEntity(
            chatId = chatId,
            role = ROLE_ASSISTANT,
            content = "",
            createdAt = System.currentTimeMillis(),
        )
        val assistant = placeholder.copy(id = messageDao.insert(placeholder))
        val accumulated = StringBuilder()
        val reasoning = StringBuilder()
        _lastTimings.value = null
        _streamingMessageId.value = assistant.id
        _streamingText.value = ""
        _streamingReasoning.value = ""
        try {
            val request = ChatRequest(
                messages = messageDao.getForChat(chatId)
                    .filter { it.id != assistant.id }
                    .map { ApiMessage(role = it.role, content = it.content) },
                systemPrompt = _systemPrompt.value.trim().ifEmpty { null },
                temperature = _params.value.temperature,
                topP = _params.value.topP,
                topK = _params.value.topK,
                minP = _params.value.minP,
                repeatPenalty = _params.value.repeatPenalty,
            )
            val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
            val result = chatClient.stream(serverUrl, apiKey, request) { delta, isReasoning ->
                if (isReasoning) {
                    reasoning.append(delta)
                    _streamingReasoning.value = reasoning.toString()
                } else {
                    accumulated.append(delta)
                    _streamingText.value = accumulated.toString()
                }
            }
            result.fold(
                onSuccess = { streamed ->
                    _lastTimings.value = streamed.timings
                    messageDao.update(
                        assistant.copy(
                            content = streamed.content.ifEmpty { accumulated.toString() },
                            reasoning = reasoning.toString().ifEmpty { null },
                            tokensIn = streamed.timings?.promptN,
                            tokensOut = streamed.timings?.predictedN,
                            tps = streamed.timings?.predictedPerSecond,
                        ),
                    )
                    chatDao.updateTimestamp(chatId, System.currentTimeMillis())
                    maybeGenerateTitle(chatId)
                },
                onFailure = { error ->
                    val fallback = error.message ?: application.getString(R.string.chat_error_send)
                    messageDao.update(
                        assistant.copy(
                            content = accumulated.toString().ifEmpty { fallback },
                            reasoning = reasoning.toString().ifEmpty { null },
                        ),
                    )
                    chatDao.updateTimestamp(chatId, System.currentTimeMillis())
                    _errors.trySend(fallback)
                },
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val partial = accumulated.toString()
                val partialReasoning = reasoning.toString().ifEmpty { null }
                if (partial.isEmpty() && partialReasoning == null) {
                    messageDao.delete(assistant)
                } else {
                    messageDao.update(assistant.copy(content = partial, reasoning = partialReasoning))
                    chatDao.updateTimestamp(chatId, System.currentTimeMillis())
                }
            }
            throw e
        } finally {
            _streamingText.value = null
            _streamingReasoning.value = null
            _streamingMessageId.value = null
        }
    }

    private fun maybeGenerateTitle(chatId: Long) {
        viewModelScope.launch {
            try {
                val serverUrl = engineManager.serverUrl ?: return@launch
                val history = messageDao.getForChat(chatId)
                if (history.size != TITLE_EXCHANGE_MESSAGES) return@launch
                val userMessage = history.firstOrNull { it.role == ROLE_USER } ?: return@launch
                val chat = chatDao.observeById(chatId).first() ?: return@launch
                val autoTitle = chatTitleFrom(userMessage.content)
                if (!shouldGenerateTitle(history.size, chat.title, autoTitle)) return@launch
                val assistantMessage = history.lastOrNull { it.role == ROLE_ASSISTANT }?.content.orEmpty()
                val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
                val generated = chatClient.complete(
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    request = buildTitleRequest(userMessage.content, assistantMessage),
                    timeoutSeconds = TITLE_TIMEOUT_SECONDS,
                ).getOrNull()
                val title = sanitizeGeneratedTitle(generated) ?: return@launch
                chatDao.updateTitleIfMatches(chatId, title, autoTitle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun MessageEntity.toUi(): MessageUi = MessageUi(
        id = id,
        role = role,
        content = content,
        reasoning = reasoning,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        tps = tps,
    )

    companion object {

        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L

        fun factory(chatId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AiPacaApp
                ChatViewModel(chatId, app, app.container)
            }
        }
    }
}

private const val CHAT_TITLE_MAX_LENGTH = 40

private const val TITLE_TIMEOUT_SECONDS = 30L

fun chatTitleFrom(text: String): String =
    text.replace(Regex("\\s+"), " ").trim().take(CHAT_TITLE_MAX_LENGTH)
