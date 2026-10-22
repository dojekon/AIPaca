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
import com.aipaca.android.engine.agent.AgentLoop
import com.aipaca.android.engine.agent.AgentLoopCallbacks
import com.aipaca.android.engine.agent.AgentLoopConfig
import com.aipaca.android.engine.agent.AgentLoopOutcome
import com.aipaca.android.engine.agent.AgentToolCall
import com.aipaca.android.engine.agent.REASONING_BUDGET_MESSAGE
import com.aipaca.android.engine.agent.COMPACTION_MIN_MESSAGES
import com.aipaca.android.engine.agent.probeActiveChatTemplate
import com.aipaca.android.engine.agent.ToolResult
import com.aipaca.android.engine.agent.buildAgentSystemPrompt
import com.aipaca.android.engine.agent.buildCompactionRequest
import com.aipaca.android.engine.agent.contextBudget
import com.aipaca.android.engine.agent.decodeToolCalls
import com.aipaca.android.engine.agent.encodeToolCalls
import com.aipaca.android.engine.agent.encodeToolResult
import com.aipaca.android.engine.agent.sanitizeCompactionSummary
import com.aipaca.android.engine.agent.shouldCompact
import com.aipaca.android.engine.agent.tools.AgentToolRegistry
import com.aipaca.android.engine.agent.tools.agentSandboxRoot
import com.aipaca.android.engine.agent.tools.builtinTools
import com.aipaca.android.engine.agent.toApiMessages
import com.aipaca.android.engine.agent.trimHistory
import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ChatClient
import com.aipaca.android.engine.chat.ChatEvent
import com.aipaca.android.engine.chat.ChatRequest
import com.aipaca.android.engine.chat.ChatTimings
import com.aipaca.android.engine.chat.ToolCallAssembler
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

    private val toolSupportChecked = java.util.concurrent.atomic.AtomicBoolean(false)

    private val currentChatId = MutableStateFlow(chatId)
    private var streamJob: Job? = null
    private var compactJob: Job? = null

    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<String> = _mode.asStateFlow()

    init {
        if (chatId != 0L) {
            viewModelScope.launch {
                chatDao.observeById(chatId).collect { chat ->
                    if (chat != null) _mode.value = chat.mode
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

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _streamingReasoning = MutableStateFlow<String?>(null)
    val streamingReasoning: StateFlow<String?> = _streamingReasoning.asStateFlow()

    private val _streamingToolCalls = MutableStateFlow<List<StreamingToolCallUi>>(emptyList())
    val streamingToolCalls: StateFlow<List<StreamingToolCallUi>> = _streamingToolCalls.asStateFlow()

    private val _agentStep = MutableStateFlow<AgentStepUi?>(null)
    val agentStep: StateFlow<AgentStepUi?> = _agentStep.asStateFlow()

    private val _compacting = MutableStateFlow(false)
    val compacting: StateFlow<Boolean> = _compacting.asStateFlow()

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
            if (_mode.value == ChatMode.AGENT) runAgent(chat, serverUrl) else stream(chat, serverUrl)
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
        if (_mode.value == ChatMode.AGENT) return false
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

    fun compact(): Boolean {
        if (streamJob?.isActive == true) return false
        val chatId = currentChatId.value
        if (chatId == 0L) return false
        if (_mode.value != ChatMode.AGENT) return false
        if (_compacting.value) return false
        val serverUrl = readyServerUrl() ?: return false
        compactJob = viewModelScope.launch {
            _compacting.value = true
            try {
                val history = messageDao.getForChat(chatId)
                if (history.size < COMPACTION_MIN_MESSAGES) return@launch
                val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
                val summary = summarize(serverUrl, apiKey, toApiMessages(history))
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
                Log.i(TAG, "Context compacted manually: ${history.size} messages -> summary")
            } finally {
                _compacting.value = false
            }
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

    fun updateMaxIterations(value: Int) {
        _params.update { it.copy(maxIterations = value.coerceIn(1, 100)) }
    }

    fun updateReasoningBudget(value: Int) {
        _params.update { it.copy(reasoningBudget = value.coerceIn(0, 4096)) }
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
                mode = _mode.value,
            ),
        )
        currentChatId.value = id
        return id
    }

    private suspend fun runAgent(chatId: Long, serverUrl: String) {
        val sandbox = agentSandboxRoot(application.filesDir)
        sandbox.mkdirs()
        val tools = builtinTools(sandbox)
        val registry = AgentToolRegistry(tools)
        val systemPrompt = buildAgentSystemPrompt(sandbox, tools)
        val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
        val apiMessages = toApiMessages(messageDao.getForChat(chatId))

        val toolsChars = registry.manifest.sumOf {
            it.parametersJsonSchema.length + it.description.length + it.name.length
        }
        val budget = contextBudget(engineManager.contextSize ?: AGENT_FALLBACK_CONTEXT, toolsChars)
        val contextPreparer: suspend (List<ApiMessage>) -> List<ApiMessage> = preparer@ { messages ->
            val trimmed = trimHistory(messages, budget)
            if (trimmed.estimatedTokens <= budget ||
                !shouldCompact(trimmed.estimatedTokens, budget, trimmed.messages.size)
            ) {
                return@preparer trimmed.messages
            }
            _compacting.value = true
            try {
                val summary = summarize(serverUrl, apiKey, trimmed.messages)
                if (summary == null) {
                    Log.i(TAG, "Context compaction failed; using trimmed history")
                    return@preparer trimmed.messages
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
                Log.i(TAG, "Context compacted: ${trimmed.messages.size} messages -> summary")
                listOf(ApiMessage(role = ROLE_SYSTEM, content = summary))
            } finally {
                _compacting.value = false
            }
        }

        val text = StringBuilder()
        val reasoning = StringBuilder()
        var toolAssembler = ToolCallAssembler()
        var assistantSavedThisStep = false

        val callbacks = object : AgentLoopCallbacks {
            override suspend fun onAssistantStart() {
                text.clear()
                reasoning.clear()
                assistantSavedThisStep = false
                _streamingText.value = ""
                _streamingReasoning.value = ""
                _streamingToolCalls.value = emptyList()
                toolAssembler = ToolCallAssembler()
            }

            override fun onTextDelta(textDelta: String, isReasoning: Boolean) {
                if (isReasoning) {
                    reasoning.append(textDelta)
                    _streamingReasoning.value = reasoning.toString()
                } else {
                    text.append(textDelta)
                    _streamingText.value = text.toString()
                }
            }

            override fun onToolCallDelta(delta: ChatEvent.ToolCallDelta) {
                toolAssembler.add(delta)
                _streamingToolCalls.value = toolAssembler.finish().map {
                    StreamingToolCallUi(it.index, it.name.orEmpty(), it.argumentsJson)
                }
            }

            override fun onStep(step: Int, maxIterations: Int) {
                _agentStep.value = AgentStepUi(step, maxIterations)
            }

            override suspend fun onAssistantSaved(
                content: String,
                toolCalls: List<AgentToolCall>,
                timings: ChatTimings?,
            ) {
                assistantSavedThisStep = true
                messageDao.insert(
                    MessageEntity(
                        chatId = chatId,
                        role = ROLE_ASSISTANT,
                        content = content,
                        reasoning = reasoning.toString().ifEmpty { null },
                        toolCalls = encodeToolCalls(toolCalls).takeIf { toolCalls.isNotEmpty() },
                        createdAt = System.currentTimeMillis(),
                        tokensIn = timings?.promptN,
                        tokensOut = timings?.predictedN,
                        tps = timings?.predictedPerSecond,
                    ),
                )
                chatDao.updateTimestamp(chatId, System.currentTimeMillis())
                text.clear()
                reasoning.clear()
                toolAssembler = ToolCallAssembler()
                _streamingText.value = ""
                _streamingReasoning.value = ""
                _streamingToolCalls.value = emptyList()
            }

            override suspend fun onToolStarted(call: AgentToolCall) {
            }

            override suspend fun onToolFinished(call: AgentToolCall, result: ToolResult) {
                messageDao.insert(
                    MessageEntity(
                        chatId = chatId,
                        role = ROLE_TOOL,
                        content = encodeToolResult(result),
                        toolCallId = call.id,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                chatDao.updateTimestamp(chatId, System.currentTimeMillis())
            }
        }

        try {
            val outcome = AgentLoop(chatClient, registry).run(
                serverUrl = serverUrl,
                apiKey = apiKey,
                systemPrompt = systemPrompt,
                history = apiMessages,
                config = AgentLoopConfig(
                    maxIterations = _params.value.maxIterations.coerceIn(1, 100),
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
                ),
                contextPreparer = contextPreparer,
                callbacks = callbacks,
            )
            when (outcome) {
                is AgentLoopOutcome.Completed -> maybeGenerateTitle(chatId)
                is AgentLoopOutcome.LimitReached ->
                    _errors.trySend(application.getString(R.string.agent_error_max_iterations))
                is AgentLoopOutcome.Failed -> _errors.trySend(outcome.message)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (!assistantSavedThisStep && (text.isNotEmpty() || reasoning.isNotEmpty())) {
                    messageDao.insert(
                        MessageEntity(
                            chatId = chatId,
                            role = ROLE_ASSISTANT,
                            content = text.toString(),
                            reasoning = reasoning.toString().ifEmpty { null },
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                chatDao.updateTimestamp(chatId, System.currentTimeMillis())
            }
            throw e
        } finally {
            _streamingText.value = null
            _streamingReasoning.value = null
            _streamingToolCalls.value = emptyList()
            _agentStep.value = null
        }
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
                reasoningBudget = _params.value.reasoningBudget.takeIf { it > 0 },
                reasoningBudgetMessage = if (_params.value.reasoningBudget > 0) {
                    REASONING_BUDGET_MESSAGE
                } else {
                    null
                },
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

    private suspend fun summarize(
        serverUrl: String,
        apiKey: String?,
        history: List<ApiMessage>,
    ): String? = try {
        chatClient.complete(
            serverUrl = serverUrl,
            apiKey = apiKey,
            request = buildCompactionRequest(history),
            timeoutSeconds = COMPACTION_TIMEOUT_SECONDS,
        ).getOrNull()?.let { sanitizeCompactionSummary(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun maybeGenerateTitle(chatId: Long) {
        viewModelScope.launch {
            try {
                val serverUrl = engineManager.serverUrl ?: return@launch
                val history = messageDao.getForChat(chatId)
                val conversational = history.filter {
                    it.role == ROLE_USER || (it.role == ROLE_ASSISTANT && decodeToolCalls(it.toolCalls).isEmpty())
                }
                if (conversational.size != TITLE_EXCHANGE_MESSAGES) return@launch
                val userMessage = conversational.firstOrNull { it.role == ROLE_USER } ?: return@launch
                val chat = chatDao.observeById(chatId).first() ?: return@launch
                val autoTitle = chatTitleFrom(userMessage.content)
                if (!shouldGenerateTitle(conversational.size, chat.title, autoTitle)) return@launch
                val assistantMessage = conversational.lastOrNull { it.role == ROLE_ASSISTANT }?.content.orEmpty()
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

        private const val AGENT_FALLBACK_CONTEXT = 4096

        private const val COMPACTION_TIMEOUT_SECONDS = 60L

        private const val TAG = "AgentContext"

        fun factory(chatId: Long, initialMode: String = ChatMode.CHAT): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AiPacaApp
                ChatViewModel(chatId, initialMode, app, app.container)
            }
        }
    }
}

private const val CHAT_TITLE_MAX_LENGTH = 40

private const val TITLE_TIMEOUT_SECONDS = 30L

fun chatTitleFrom(text: String): String =
    text.replace(Regex("\\s+"), " ").trim().take(CHAT_TITLE_MAX_LENGTH)
