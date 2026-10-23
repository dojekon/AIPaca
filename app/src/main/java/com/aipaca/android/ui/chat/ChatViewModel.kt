package com.aipaca.android.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aipaca.android.AiPacaApp
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.ChatEntity
import com.aipaca.android.data.db.entity.ChatMode
import com.aipaca.android.data.db.entity.MessageEntity
import com.aipaca.android.data.hf.containsToolMarkers
import com.aipaca.android.engine.EngineState
import com.aipaca.android.engine.agent.AgentLoopConfig
import com.aipaca.android.engine.agent.AgentToolCall
import com.aipaca.android.engine.agent.REASONING_BUDGET_MESSAGE
import com.aipaca.android.engine.agent.COMPACTION_MIN_MESSAGES
import com.aipaca.android.engine.agent.probeActiveChatTemplate
import com.aipaca.android.engine.agent.contextBudget
import com.aipaca.android.engine.agent.MAX_CONTEXT_OVERFLOW_RETRIES
import com.aipaca.android.engine.agent.parseContextOverflow
import com.aipaca.android.engine.agent.trimWithHysteresis
import com.aipaca.android.engine.agent.shrunkBudgetScale
import com.aipaca.android.engine.agent.MIN_CONTEXT_BUDGET
import com.aipaca.android.engine.agent.compactionInput
import com.aipaca.android.engine.agent.agentHistory
import com.aipaca.android.engine.agent.decodeToolCalls
import com.aipaca.android.engine.agent.toApiMessages
import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ChatClient
import com.aipaca.android.engine.chat.ChatRequest
import com.aipaca.android.engine.chat.ChatStreamResult
import com.aipaca.android.engine.chat.ChatTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    chatId: Long,
    initialMode: String = ChatMode.CHAT,
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
        val toolCalls: List<AgentToolCall> = emptyList(),
        val toolCallId: String? = null,
    )

    data class StreamingToolCallUi(val index: Int, val name: String, val argsJson: String)

    data class AgentStepUi(val step: Int, val maxIterations: Int)

    data class ChatParamsUi(
        val temperature: Double = 0.7,
        val topP: Double = 0.95,
        val topK: Int = 40,
        val minP: Double = 0.05,
        val repeatPenalty: Double = 1.1,
        val maxIterations: Int = 25,
        val reasoningBudget: Int = DEFAULT_REASONING_BUDGET,
    )

    private val chatDao = container.database.chatDao()
    private val messageDao = container.database.messageDao()
    private val modelDao = container.database.modelDao()
    private val engineManager = container.engineManager
    private val settingsRepository = container.settingsRepository
    private val chatClient = ChatClient()
    private val agentRunner = container.agentRunner
    private val titles = ChatTitleGenerator(container, chatClient)

    private val toolSupportChecked = java.util.concurrent.atomic.AtomicBoolean(false)

    private val currentChatId = MutableStateFlow(chatId)
    private var streamJob: Job? = null
    private var compactJob: Job? = null
    private var settingsJob: Job? = null

    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<String> = _mode.asStateFlow()

    init {
        viewModelScope.launch {
            agentRunner.events.collect { event ->
                if (event.chatId == currentChatId.value) _errors.trySend(event.message)
            }
        }
        if (chatId != 0L) {
            viewModelScope.launch {
                var settingsLoaded = false
                chatDao.observeById(chatId).collect { chat ->
                    if (chat == null) return@collect
                    if (!settingsLoaded) {
                        settingsLoaded = true
                        _mode.value = chat.mode
                        _params.value = decodeChatParams(chat.paramsJson, chat.mode)
                        _systemPrompt.value = chat.systemPrompt.orEmpty()
                    } else if (chat.mode != _mode.value) {
                        if (_params.value == defaultChatParams(_mode.value)) {
                            _params.value = defaultChatParams(chat.mode)
                        }
                        _mode.value = chat.mode
                    }
                }
            }
        }
        viewModelScope.launch {
            try {
                val ready = engineManager.state.first { it is EngineState.Ready } as EngineState.Ready
                if (!toolSupportChecked.compareAndSet(false, true)) return@launch
                val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
                val serverUrl = engineManager.serverUrl ?: return@launch
                val template = withContext(Dispatchers.IO) {
                    probeActiveChatTemplate(serverUrl, apiKey)
                } ?: return@launch
                val markers = containsToolMarkers(template)
                val entity = modelDao.getByPath(ready.modelPath) ?: return@launch
                val newValue = when {
                    markers -> 1
                    entity.toolsSupported == 1 -> 0
                    else -> entity.toolsSupported
                }
                if (newValue != entity.toolsSupported) {
                    modelDao.updateToolsSupported(ready.modelPath, newValue)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    private val agentRun: StateFlow<AgentRunState?> = combine(currentChatId, agentRunner.runs) { id, runs -> runs[id] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = combine(_streamingText, agentRun) { own, run -> run?.text ?: own }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _streamingReasoning = MutableStateFlow<String?>(null)
    val streamingReasoning: StateFlow<String?> = combine(_streamingReasoning, agentRun) { own, run ->
        run?.reasoning ?: own
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val streamingToolCalls: StateFlow<List<StreamingToolCallUi>> = agentRun
        .map { run ->
            run?.toolCalls.orEmpty().map { StreamingToolCallUi(it.index, it.name.orEmpty(), it.argumentsJson) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val agentStep: StateFlow<AgentStepUi?> = agentRun
        .map { run -> run?.takeIf { it.step > 0 }?.let { AgentStepUi(it.step, it.maxIterations) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _compacting = MutableStateFlow(false)
    val compacting: StateFlow<Boolean> = combine(_compacting, agentRun) { own, run -> own || run?.compacting == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _streamingMessageId = MutableStateFlow<Long?>(null)
    val streamingMessageId: StateFlow<Long?> = _streamingMessageId.asStateFlow()

    private val _lastTimings = MutableStateFlow<ChatTimings?>(null)
    val lastTimings: StateFlow<ChatTimings?> = _lastTimings.asStateFlow()

    private val _params = MutableStateFlow(defaultChatParams(initialMode))
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

    private fun busy(): Boolean = streamJob?.isActive == true || agentRunner.isRunning(currentChatId.value)

    fun send(text: String): Boolean {
        val content = text.trim()
        if (content.isEmpty() || busy()) return false
        val serverUrl = readyServerUrl() ?: return false
        streamJob = viewModelScope.launch {
            val chat = ensureChat(content)
            val sentAt = System.currentTimeMillis()
            messageDao.insert(
                MessageEntity(
                    chatId = chat,
                    role = ROLE_USER,
                    content = content,
                    createdAt = sentAt,
                ),
            )
            if (_mode.value == ChatMode.AGENT) {
                agentRunner.start(chat, serverUrl, sentAt, agentConfig())
            } else {
                stream(chat, serverUrl)
            }
        }
        return true
    }

    fun stop() {
        streamJob?.cancel()
        currentChatId.value.takeIf { it != 0L }?.let(agentRunner::stop)
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
        agentRunner.stop(id)
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
        if (_mode.value == ChatMode.AGENT) return false
        if (busy()) return false
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

    fun compact(): Boolean {
        if (busy()) return false
        val chatId = currentChatId.value
        if (chatId == 0L) return false
        if (_mode.value != ChatMode.AGENT) return false
        if (_compacting.value) return false
        val serverUrl = readyServerUrl() ?: return false
        compactJob = viewModelScope.launch {
            _compacting.value = true
            try {
                val history = agentHistory(messageDao.getForChat(chatId))
                if (history.messages.size < COMPACTION_MIN_MESSAGES) return@launch
                val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
                val summary = agentRunner.summarize(
                    serverUrl,
                    apiKey,
                    compactionInput(history.summary, toApiMessages(history.messages)),
                )
                if (summary == null) {
                    _errors.trySend(application.getString(R.string.agent_error_compaction))
                    return@launch
                }
                val now = System.currentTimeMillis()
                messageDao.insert(
                    MessageEntity(
                        chatId = chatId,
                        role = ROLE_SYSTEM,
                        content = summary,
                        createdAt = now,
                    ),
                )
                chatDao.updateTimestamp(chatId, now)
                Log.i(TAG, "Context compacted manually: ${history.messages.size} messages -> summary")
            } finally {
                _compacting.value = false
            }
        }
        return true
    }

    fun updateTemperature(value: Double) {
        _params.update { it.copy(temperature = value) }
        persistSettings()
    }

    fun updateTopP(value: Double) {
        _params.update { it.copy(topP = value) }
        persistSettings()
    }

    fun updateTopK(value: Int) {
        _params.update { it.copy(topK = value) }
        persistSettings()
    }

    fun updateMinP(value: Double) {
        _params.update { it.copy(minP = value) }
        persistSettings()
    }

    fun updateRepeatPenalty(value: Double) {
        _params.update { it.copy(repeatPenalty = value) }
        persistSettings()
    }

    fun updateMaxIterations(value: Int) {
        _params.update { it.copy(maxIterations = value.coerceIn(1, 100)) }
        persistSettings()
    }

    fun updateReasoningBudget(value: Int) {
        _params.update { it.copy(reasoningBudget = value.coerceIn(0, 4096)) }
        persistSettings()
    }

    fun updateSystemPrompt(value: String) {
        _systemPrompt.value = value
        persistSettings()
    }

    fun resetParams() {
        _params.value = defaultChatParams(_mode.value)
        persistSettings()
    }

    private fun readyServerUrl(): String? {
        val url = engineManager.serverUrl
        if (url == null) {
            _errors.trySend(application.getString(R.string.chat_error_engine_off))
        }
        return url
    }

    private fun agentConfig(): AgentLoopConfig {
        val params = _params.value
        return AgentLoopConfig(
            maxIterations = params.maxIterations.coerceIn(1, 100),
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            minP = params.minP,
            repeatPenalty = params.repeatPenalty,
            reasoningBudget = params.reasoningBudget.takeIf { it > 0 },
            reasoningBudgetMessage = if (params.reasoningBudget > 0) REASONING_BUDGET_MESSAGE else null,
        )
    }

    private fun persistSettings() {
        val id = currentChatId.value
        if (id == 0L) return
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            delay(SETTINGS_SAVE_DEBOUNCE_MS)
            chatDao.updateSettings(
                id,
                encodeChatParams(_params.value, _mode.value),
                _systemPrompt.value.trim().ifEmpty { null },
            )
        }
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
                mode = _mode.value,
                systemPrompt = _systemPrompt.value.trim().ifEmpty { null },
                paramsJson = encodeChatParams(_params.value, _mode.value),
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
        val throttle = StreamThrottle()
        _lastTimings.value = null
        _streamingMessageId.value = assistant.id
        _streamingText.value = ""
        _streamingReasoning.value = ""
        try {
            val history = messageDao.getForChat(chatId)
                .filter { it.id != assistant.id }
                .map { ApiMessage(role = it.role, content = it.content) }
            val systemPrompt = _systemPrompt.value.trim().ifEmpty { null }
            val budget = contextBudget(
                engineManager.contextSize ?: FALLBACK_CONTEXT,
                systemPrompt?.length ?: 0,
            )
            val baseRequest = ChatRequest(
                messages = history,
                systemPrompt = systemPrompt,
                temperature = _params.value.temperature,
                topP = _params.value.topP,
                topK = _params.value.topK,
                minP = _params.value.minP,
                repeatPenalty = _params.value.repeatPenalty,
                reasoningBudget = _params.value.reasoningBudget.takeIf { it > 0 },
                reasoningBudgetMessage = if (_params.value.reasoningBudget > 0) {
                    REASONING_BUDGET_MESSAGE
                } else {
                    null
                },
            )
            val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
            var budgetScale = 1.0
            var overflowRetries = 0
            var result: Result<ChatStreamResult>
            while (true) {
                val scaledBudget = (budget * budgetScale).toInt().coerceAtLeast(MIN_CONTEXT_BUDGET)
                val request = baseRequest.copy(messages = trimWithHysteresis(history, scaledBudget).messages)
                result = chatClient.stream(serverUrl, apiKey, request) { delta, isReasoning ->
                    if (isReasoning) reasoning.append(delta) else accumulated.append(delta)
                    if (throttle.ready()) {
                        _streamingReasoning.value = reasoning.toString()
                        _streamingText.value = accumulated.toString()
                    }
                }
                _streamingReasoning.value = reasoning.toString()
                _streamingText.value = accumulated.toString()
                val overflow = parseContextOverflow(result.exceptionOrNull()?.message)
                if (overflow == null || overflowRetries >= MAX_CONTEXT_OVERFLOW_RETRIES || accumulated.isNotEmpty()) break
                budgetScale = shrunkBudgetScale(budgetScale, overflow)
                overflowRetries++
                Log.i(TAG, "Chat context overflow $overflow; budget scale -> $budgetScale")
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
                    viewModelScope.launch { titles.maybeGenerate(chatId) }
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

    private fun MessageEntity.toUi(): MessageUi = MessageUi(
        id = id,
        role = role,
        content = content,
        reasoning = reasoning,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        tps = tps,
        toolCalls = decodeToolCalls(toolCalls),
        toolCallId = toolCallId,
    )

    companion object {

        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
        const val ROLE_SYSTEM = "system"

        const val DEFAULT_REASONING_BUDGET = 512

        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L

        private const val FALLBACK_CONTEXT = 4096




        private const val TAG = "AgentContext"


        private const val SETTINGS_SAVE_DEBOUNCE_MS = 300L

        fun factory(chatId: Long, initialMode: String = ChatMode.CHAT): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AiPacaApp
                ChatViewModel(chatId, initialMode, app, app.container)
            }
        }
    }
}

private const val CHAT_TITLE_MAX_LENGTH = 40


fun chatTitleFrom(text: String): String =
    text.replace(Regex("\\s+"), " ").trim().take(CHAT_TITLE_MAX_LENGTH)
