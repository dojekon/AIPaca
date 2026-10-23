package com.aipaca.android.ui.chat

import android.content.Context
import android.util.Log
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.MessageEntity
import com.aipaca.android.engine.agent.AgentLoop
import com.aipaca.android.engine.agent.AgentLoopCallbacks
import com.aipaca.android.engine.agent.AgentLoopConfig
import com.aipaca.android.engine.agent.AgentLoopOutcome
import com.aipaca.android.engine.agent.AgentToolCall
import com.aipaca.android.engine.agent.MIN_CONTEXT_BUDGET
import com.aipaca.android.engine.agent.ToolResult
import com.aipaca.android.engine.agent.agentHistory
import com.aipaca.android.engine.agent.buildAgentSystemPrompt
import com.aipaca.android.engine.agent.buildCompactionRequest
import com.aipaca.android.engine.agent.compactionInput
import com.aipaca.android.engine.agent.contextBudget
import com.aipaca.android.engine.agent.encodeToolCalls
import com.aipaca.android.engine.agent.encodeToolResult
import com.aipaca.android.engine.agent.estimateTokens
import com.aipaca.android.engine.agent.fetchPageCharLimit
import com.aipaca.android.engine.agent.sanitizeCompactionSummary
import com.aipaca.android.engine.agent.shouldCompact
import com.aipaca.android.engine.agent.shrunkBudgetScale
import com.aipaca.android.engine.agent.splitForCompaction
import com.aipaca.android.engine.agent.toApiMessages
import com.aipaca.android.engine.agent.toolOutputCharLimit
import com.aipaca.android.engine.agent.tools.AgentToolRegistry
import com.aipaca.android.engine.agent.tools.agentSandboxRoot
import com.aipaca.android.engine.agent.tools.builtinTools
import com.aipaca.android.engine.agent.trimWithHysteresis
import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.AssembledToolCall
import com.aipaca.android.engine.chat.ChatClient
import com.aipaca.android.engine.chat.ChatEvent
import com.aipaca.android.engine.chat.ChatTimings
import com.aipaca.android.engine.chat.ToolCallAssembler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AgentRunState(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<AssembledToolCall> = emptyList(),
    val step: Int = 0,
    val maxIterations: Int = 0,
    val compacting: Boolean = false,
)

data class AgentRunEvent(val chatId: Long, val message: String)

class AgentSessionRunner(
    private val context: Context,
    container: AppContainer,
    private val scope: CoroutineScope,
    private val titles: ChatTitleGenerator,
    private val notifier: AgentNotifier,
    private val chatClient: ChatClient = ChatClient(),
) {
    private val chatDao = container.database.chatDao()
    private val messageDao = container.database.messageDao()
    private val engineManager = container.engineManager
    private val settingsRepository = container.settingsRepository

    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _runs = MutableStateFlow<Map<Long, AgentRunState>>(emptyMap())
    val runs: StateFlow<Map<Long, AgentRunState>> = _runs.asStateFlow()

    private val _events = MutableSharedFlow<AgentRunEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<AgentRunEvent> = _events.asSharedFlow()

    fun isRunning(chatId: Long): Boolean = jobs[chatId]?.isActive == true

    fun start(chatId: Long, serverUrl: String, turnStartedAt: Long, config: AgentLoopConfig): Boolean {
        if (isRunning(chatId)) return false
        _runs.update { it + (chatId to AgentRunState(maxIterations = config.maxIterations)) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(chatId, serverUrl, turnStartedAt, config)
            } finally {
                jobs.remove(chatId)
                _runs.update { it - chatId }
            }
        }
        jobs[chatId] = job
        job.start()
        return true
    }

    fun stop(chatId: Long) {
        jobs[chatId]?.cancel()
    }

    suspend fun summarize(serverUrl: String, apiKey: String?, history: List<ApiMessage>): String? = try {
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

    private fun updateRun(chatId: Long, transform: (AgentRunState) -> AgentRunState) {
        _runs.update { runs -> runs[chatId]?.let { runs + (chatId to transform(it)) } ?: runs }
    }

    private suspend fun run(chatId: Long, serverUrl: String, turnStartedAt: Long, baseConfig: AgentLoopConfig) {
        val sandbox = agentSandboxRoot(context.filesDir)
        sandbox.mkdirs()
        val nCtx = engineManager.contextSize ?: FALLBACK_CONTEXT
        val toolOutputChars = toolOutputCharLimit(contextBudget(nCtx, TOOLS_RESERVE_CHARS))
        val tools = builtinTools(sandbox, fetchPageChars = { fetchPageCharLimit(toolOutputChars) })
        val registry = AgentToolRegistry(tools)
        val systemPrompt = buildAgentSystemPrompt(tools)
        val apiKey = settingsRepository.settings.first().serverApiKey.trim().ifEmpty { null }
        val history = agentHistory(messageDao.getForChat(chatId))
        var contextSummary = history.summary
        val apiMessages = toApiMessages(history.messages)

        val toolsChars = registry.manifest.sumOf {
            it.parametersJsonSchema.length + it.description.length + it.name.length
        }
        val budget = contextBudget(nCtx, toolsChars)
        var budgetScale = 1.0
        fun messagesBudget(): Int =
            ((budget * budgetScale).toInt() - estimateTokens(contextSummary.orEmpty()))
                .coerceAtLeast(MIN_CONTEXT_BUDGET)
        val contextPreparer: suspend (List<ApiMessage>) -> List<ApiMessage> = preparer@ { messages ->
            val trimmed = trimWithHysteresis(messages, messagesBudget())
            if (trimmed.estimatedTokens <= messagesBudget() ||
                !shouldCompact(trimmed.estimatedTokens, messagesBudget(), trimmed.messages.size)
            ) {
                return@preparer trimmed.messages
            }
            val split = splitForCompaction(messages)
            if (split.older.isEmpty()) return@preparer trimmed.messages
            updateRun(chatId) { it.copy(compacting = true) }
            try {
                val summary = summarize(serverUrl, apiKey, compactionInput(contextSummary, split.older))
                if (summary == null) {
                    Log.i(TAG, "Context compaction failed; using trimmed history")
                    return@preparer trimmed.messages
                }
                messageDao.insert(
                    MessageEntity(
                        chatId = chatId,
                        role = ChatViewModel.ROLE_SYSTEM,
                        content = summary,
                        createdAt = turnStartedAt - 1,
                    ),
                )
                chatDao.updateTimestamp(chatId, System.currentTimeMillis())
                contextSummary = summary
                Log.i(TAG, "Context compacted: ${split.older.size} messages -> summary")
                trimWithHysteresis(split.activeTurn, messagesBudget()).messages
            } finally {
                updateRun(chatId) { it.copy(compacting = false) }
            }
        }

        val streamedText = StringBuilder()
        val reasoning = StringBuilder()
        var toolAssembler = ToolCallAssembler()
        var assistantSavedThisStep = false
        val throttle = StreamThrottle()
        fun publish() {
            val calls = toolAssembler.finish()
            updateRun(chatId) {
                it.copy(text = streamedText.toString(), reasoning = reasoning.toString(), toolCalls = calls)
            }
        }

        val callbacks = object : AgentLoopCallbacks {
            override suspend fun onAssistantStart() {
                throttle.reset()
                streamedText.clear()
                reasoning.clear()
                assistantSavedThisStep = false
                toolAssembler = ToolCallAssembler()
                publish()
            }

            override fun onTextDelta(text: String, isReasoning: Boolean) {
                if (isReasoning) reasoning.append(text) else streamedText.append(text)
                if (throttle.ready()) publish()
            }

            override fun onToolCallDelta(delta: ChatEvent.ToolCallDelta) {
                toolAssembler.add(delta)
                if (throttle.ready()) publish()
            }

            override fun onStep(step: Int, maxIterations: Int) {
                updateRun(chatId) { it.copy(step = step, maxIterations = maxIterations) }
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
                        role = ChatViewModel.ROLE_ASSISTANT,
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
                streamedText.clear()
                reasoning.clear()
                toolAssembler = ToolCallAssembler()
                publish()
            }

            override suspend fun onToolStarted(call: AgentToolCall) {
            }

            override suspend fun onToolFinished(call: AgentToolCall, result: ToolResult) {
                messageDao.insert(
                    MessageEntity(
                        chatId = chatId,
                        role = ChatViewModel.ROLE_TOOL,
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
                config = baseConfig.copy(toolOutputMaxChars = toolOutputChars),
                contextPreparer = contextPreparer,
                contextSummary = { contextSummary },
                onContextOverflow = { overflow ->
                    budgetScale = shrunkBudgetScale(budgetScale, overflow)
                    Log.i(TAG, "Context overflow $overflow; budget scale -> $budgetScale")
                    true
                },
                callbacks = callbacks,
            )
            when (outcome) {
                is AgentLoopOutcome.Completed -> {
                    titles.maybeGenerate(chatId)
                    notifier.notifyFinished(chatId, chatDao.getById(chatId)?.title, null)
                }
                is AgentLoopOutcome.LimitReached -> {
                    val message = context.getString(R.string.agent_error_max_iterations)
                    _events.tryEmit(AgentRunEvent(chatId, message))
                    notifier.notifyFinished(chatId, chatDao.getById(chatId)?.title, message)
                }
                is AgentLoopOutcome.Failed -> {
                    _events.tryEmit(AgentRunEvent(chatId, outcome.message))
                    notifier.notifyFinished(chatId, chatDao.getById(chatId)?.title, outcome.message)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (!assistantSavedThisStep && (streamedText.isNotEmpty() || reasoning.isNotEmpty())) {
                    messageDao.insert(
                        MessageEntity(
                            chatId = chatId,
                            role = ChatViewModel.ROLE_ASSISTANT,
                            content = streamedText.toString(),
                            reasoning = reasoning.toString().ifEmpty { null },
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                chatDao.updateTimestamp(chatId, System.currentTimeMillis())
            }
            throw e
        }
    }

    private companion object {
        const val TAG = "AgentContext"
        const val FALLBACK_CONTEXT = 4096
        const val TOOLS_RESERVE_CHARS = 2400
        const val COMPACTION_TIMEOUT_SECONDS = 60L
        const val EVENT_BUFFER = 16
    }
}
