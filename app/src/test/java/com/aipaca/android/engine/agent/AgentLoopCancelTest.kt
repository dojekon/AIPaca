package com.aipaca.android.engine.agent

import com.aipaca.android.engine.agent.tools.AgentToolRegistry
import com.aipaca.android.engine.chat.AssembledToolCall
import com.aipaca.android.engine.chat.ChatEvent
import com.aipaca.android.engine.chat.ChatRequest
import com.aipaca.android.engine.chat.ChatStreamResult
import com.aipaca.android.engine.chat.ChatStreamer
import com.aipaca.android.engine.chat.ChatTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopCancelTest {

    @Test
    fun cancelWhileStreaming() = runBlocking {
        val partialReceived = CompletableDeferred<Unit>()
        val streamer = FakeChatStreamer(
            listOf { _, onDelta ->
                onDelta("partial", false)
                awaitCancellation()
            },
        )
        val callbacks = RecordingCallbacks(onText = { partialReceived.complete(Unit) })
        val loop = AgentLoop(streamer, AgentToolRegistry(emptyList()))

        val job = launch {
            loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)
        }
        withTimeout(TIMEOUT_MS) { partialReceived.await() }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(listOf("partial"), callbacks.textDeltas.map { it.first })
        assertTrue(callbacks.assistantSaved.isEmpty())
        assertTrue(callbacks.toolsStarted.isEmpty())
        assertTrue(callbacks.toolsFinished.isEmpty())
    }

    @Test
    fun cancelWhileToolRunning() = runBlocking {
        val toolStarted = CompletableDeferred<Unit>()
        val streamer = FakeChatStreamer(
            listOf { _, _ ->
                ChatStreamResult(
                    content = "",
                    timings = null,
                    toolCalls = listOf(
                        AssembledToolCall(0, "c1", "slow", "{}"),
                        AssembledToolCall(1, "c2", "fast", "{}"),
                    ),
                )
            },
        )
        val slow = FakeTool("slow") { awaitCancellation() }
        val fast = FakeTool("fast") { ToolResult(AgentToolStatus.OK, "fast") }
        val callbacks = RecordingCallbacks(onToolStart = { toolStarted.complete(Unit) })
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(slow, fast)))

        val job = launch {
            loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)
        }
        withTimeout(TIMEOUT_MS) { toolStarted.await() }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, streamer.requests.size)
        assertEquals(listOf("c1"), callbacks.toolsStarted.map { it.id })
        assertEquals(2, callbacks.toolsFinished.size)
        assertEquals(listOf("c1", "c2"), callbacks.toolsFinished.map { it.first.id })
        callbacks.toolsFinished.forEach { (_, result) ->
            assertEquals(AgentToolStatus.CANCELLED, result.status)
            assertEquals("Tool execution cancelled", result.output)
        }
    }

    @Test
    fun cancelBetweenSteps() = runBlocking {
        val secondStreamStarted = CompletableDeferred<Unit>()
        val streamer = FakeChatStreamer(
            listOf(
                { _, _ ->
                    ChatStreamResult(
                        content = "first",
                        timings = null,
                        toolCalls = listOf(AssembledToolCall(0, "c1", "fast", "{}")),
                    )
                },
                { _, _ ->
                    secondStreamStarted.complete(Unit)
                    awaitCancellation()
                },
            ),
        )
        val tool = FakeTool("fast") { ToolResult(AgentToolStatus.OK, "done") }
        val callbacks = RecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(tool)))

        val job = launch {
            loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)
        }
        withTimeout(TIMEOUT_MS) { secondStreamStarted.await() }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(2, streamer.requests.size)
        assertEquals(listOf("first"), callbacks.assistantSaved)
        assertEquals(1, callbacks.toolsFinished.size)
        val (call, result) = callbacks.toolsFinished.single()
        assertEquals("c1", call.id)
        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals("done", result.output)
    }

    @Test
    fun normalCancellationIsNotConvertedToFailedOutcome() = runBlocking {
        val streamingStarted = CompletableDeferred<Unit>()
        val streamer = FakeChatStreamer(
            listOf { _, _ ->
                streamingStarted.complete(Unit)
                awaitCancellation()
            },
        )
        val callbacks = RecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(emptyList()))
        var outcome: AgentLoopOutcome? = null
        var cancellation: Throwable? = null

        val job = launch {
            try {
                outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)
            } catch (e: CancellationException) {
                cancellation = e
                throw e
            }
        }
        withTimeout(TIMEOUT_MS) { streamingStarted.await() }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(cancellation is CancellationException)
        assertNull(outcome)
        assertFalse(outcome is AgentLoopOutcome.Failed)
    }
}

private const val SERVER_URL = "http://127.0.0.1:8080"
private const val SYSTEM_PROMPT = "system"
private const val TIMEOUT_MS = 5_000L

private typealias StreamHandler = suspend (ChatRequest, (String, Boolean) -> Unit) -> ChatStreamResult

private class FakeChatStreamer(
    private val handlers: List<StreamHandler>,
) : ChatStreamer {

    val requests = mutableListOf<ChatRequest>()

    override suspend fun stream(
        serverUrl: String,
        apiKey: String?,
        request: ChatRequest,
        onToolCall: (ChatEvent.ToolCallDelta) -> Unit,
        onDelta: (String, Boolean) -> Unit,
    ): Result<ChatStreamResult> {
        val index = requests.size
        requests += request
        val handler = handlers.getOrNull(index)
            ?: throw AssertionError("Unexpected stream request #${index + 1}")
        return Result.success(handler(request, onDelta))
    }
}

private class FakeTool(
    override val name: String,
    private val block: suspend (String) -> ToolResult,
) : AgentTool {

    override val description: String = "fake tool"
    override val parametersJsonSchema: String = """{"type":"object"}"""

    override suspend fun execute(paramsJson: String): ToolResult = block(paramsJson)
}

private class RecordingCallbacks(
    private val onText: (String) -> Unit = {},
    private val onToolStart: () -> Unit = {},
) : AgentLoopCallbacks {

    val textDeltas = mutableListOf<Pair<String, Boolean>>()
    val assistantSaved = mutableListOf<String>()
    val toolsStarted = mutableListOf<AgentToolCall>()
    val toolsFinished = mutableListOf<Pair<AgentToolCall, ToolResult>>()

    override suspend fun onAssistantStart() = Unit

    override fun onTextDelta(text: String, isReasoning: Boolean) {
        textDeltas += text to isReasoning
        onText(text)
    }

    override fun onToolCallDelta(delta: ChatEvent.ToolCallDelta) = Unit

    override fun onStep(step: Int, maxIterations: Int) = Unit

    override suspend fun onAssistantSaved(
        content: String,
        toolCalls: List<AgentToolCall>,
        timings: ChatTimings?,
    ) {
        assistantSaved += content
    }

    override suspend fun onToolStarted(call: AgentToolCall) {
        toolsStarted += call
        onToolStart()
    }

    override suspend fun onToolFinished(call: AgentToolCall, result: ToolResult) {
        toolsFinished += call to result
    }
}
