package com.aipaca.android.engine.agent

import com.aipaca.android.engine.agent.tools.AgentToolRegistry
import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ChatEvent
import com.aipaca.android.engine.chat.ChatRequest
import com.aipaca.android.engine.chat.ChatStreamResult
import com.aipaca.android.engine.chat.ChatStreamer
import com.aipaca.android.engine.chat.ChatTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class AgentLoopConfig(
    val maxIterations: Int = 25,
    val temperature: Double = 0.7,
    val topP: Double = 0.95,
    val topK: Int = 40,
    val minP: Double = 0.05,
    val repeatPenalty: Double = 1.1,
    val reasoningBudget: Int? = null,
    val reasoningBudgetMessage: String? = null,
    val toolOutputMaxChars: Int = TOOL_OUTPUT_MAX_CHARS,
)

sealed interface AgentLoopOutcome {
    data class Completed(val iterations: Int) : AgentLoopOutcome
    data class LimitReached(val iterations: Int) : AgentLoopOutcome
    data class Failed(val message: String) : AgentLoopOutcome
}

interface AgentLoopCallbacks {
    suspend fun onAssistantStart()
    fun onTextDelta(text: String, isReasoning: Boolean)
    fun onToolCallDelta(delta: ChatEvent.ToolCallDelta)
    fun onStep(step: Int, maxIterations: Int)
    suspend fun onAssistantSaved(
        content: String,
        toolCalls: List<AgentToolCall>,
        timings: ChatTimings?,
    )
    suspend fun onToolStarted(call: AgentToolCall)
    suspend fun onToolFinished(call: AgentToolCall, result: ToolResult)
}

class AgentLoop(
    private val streamer: ChatStreamer,
    private val registry: AgentToolRegistry,
) {
    suspend fun run(
        serverUrl: String,
        apiKey: String?,
        systemPrompt: String,
        history: List<ApiMessage>,
        config: AgentLoopConfig = AgentLoopConfig(),
        contextPreparer: suspend (List<ApiMessage>) -> List<ApiMessage> = { it },
        contextSummary: () -> String? = { null },
        onContextOverflow: suspend (ContextOverflow) -> Boolean = { false },
        callbacks: AgentLoopCallbacks,
    ): AgentLoopOutcome {
        val working = history.toMutableList()
        val executedSignatures = mutableListOf<String>()
        suspend fun recordSynthetic(call: AgentToolCall, result: ToolResult) {
            callbacks.onToolFinished(call, result)
            working += toolApiMessage(call, result)
        }
        var iteration = 0
        while (stepsGuarded(iteration, config.maxIterations)) {
            callbacks.onStep(iteration + 1, config.maxIterations)
            var overflowRetries = 0
            var streamed: ChatStreamResult? = null
            while (streamed == null) {
                callbacks.onAssistantStart()
                val prepared = contextPreparer(working.toList())
                working.clear()
                working.addAll(prepared)
                val request = ChatRequest(
                    messages = prepared,
                    systemPrompt = withContextSummary(systemPrompt, contextSummary()),
                    temperature = config.temperature,
                    topP = config.topP,
                    topK = config.topK,
                    minP = config.minP,
                    repeatPenalty = config.repeatPenalty,
                    tools = registry.manifest,
                    toolChoice = "auto",
                    reasoningBudget = config.reasoningBudget,
                    reasoningBudgetMessage = config.reasoningBudgetMessage,
                )
                streamed = streamer.stream(
                    serverUrl,
                    apiKey,
                    request,
                    callbacks::onToolCallDelta,
                ) { text, reasoning ->
                    callbacks.onTextDelta(text, reasoning)
                }.getOrElse { error ->
                    val overflow = parseContextOverflow(error.message)
                    val retry = overflow != null &&
                        overflowRetries < MAX_CONTEXT_OVERFLOW_RETRIES &&
                        onContextOverflow(overflow)
                    if (!retry) return AgentLoopOutcome.Failed(error.message ?: "Agent request failed")
                    overflowRetries++
                    null
                }
            }
            val result = streamed
            val calls = result.toolCalls.map { call ->
                AgentToolCall(
                    id = call.id ?: "call_${call.index}",
                    name = call.name ?: "",
                    argsJson = call.argumentsJson.ifBlank { "{}" },
                )
            }
            callbacks.onAssistantSaved(result.content, calls, result.timings)
            working += assistantApiMessage(result.content, calls)
            if (calls.isEmpty()) return AgentLoopOutcome.Completed(iteration + 1)
            if (!shouldContinue(calls, iteration + 1, config.maxIterations)) {
                val limitResult = ToolResult(
                    AgentToolStatus.ERROR,
                    "Maximum number of steps reached: tool was not executed.",
                )
                for (call in calls) {
                    recordSynthetic(call, limitResult.copy())
                }
                return AgentLoopOutcome.LimitReached(iteration + 1)
            }
            var completed = 0
            try {
                for (call in calls) {
                    callbacks.onToolStarted(call)
                    val signature = toolCallSignature(call)
                    val toolResult = if (isRepeatedCall(executedSignatures, signature)) {
                        ToolResult(AgentToolStatus.ERROR, REPEATED_CALL_MESSAGE)
                    } else {
                        withContext(Dispatchers.IO) {
                            registry.execute(call.name, call.argsJson)
                        }
                    }.cappedTo(config.toolOutputMaxChars)
                    executedSignatures += signature
                    callbacks.onToolFinished(call, toolResult)
                    working += toolApiMessage(call, toolResult)
                    completed++
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val cancelledResult = ToolResult(
                        AgentToolStatus.CANCELLED,
                        "Tool execution cancelled",
                    )
                    for (index in completed until calls.size) {
                        recordSynthetic(calls[index], cancelledResult.copy())
                    }
                }
                throw e
            }
            iteration++
        }
        return AgentLoopOutcome.LimitReached(iteration)
    }
}
