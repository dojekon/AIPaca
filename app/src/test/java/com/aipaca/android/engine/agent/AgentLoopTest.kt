package com.aipaca.android.engine.agent

import com.aipaca.android.engine.agent.tools.AgentToolRegistry
import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ApiToolCall
import com.aipaca.android.engine.chat.AssembledToolCall
import com.aipaca.android.engine.chat.ChatEvent
import com.aipaca.android.engine.chat.ChatRequest
import com.aipaca.android.engine.chat.ChatStreamResult
import com.aipaca.android.engine.chat.ChatStreamer
import com.aipaca.android.engine.chat.ChatTimings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AgentLoopTest {

    @Test
    fun textOnlyReply() = runBlocking {
        val streamer = ScriptedStreamer(listOf(reply("done")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(emptyList()))

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Completed(1), outcome)
        assertEquals(1, streamer.requests.size)
        assertEquals(1, callbacks.saved.size)
        assertEquals("done", callbacks.saved.single().content)
        assertTrue(callbacks.saved.single().toolCalls.isEmpty())
        assertTrue(callbacks.started.isEmpty())
        assertTrue(callbacks.finished.isEmpty())
    }

    @Test
    fun toolThenAnswer() = runBlocking {
        val executed = mutableListOf<String>()
        val echo = TestTool("echo") { args ->
            executed += args
            ToolResult(AgentToolStatus.OK, "echo:$args")
        }
        val call = AssembledToolCall(0, "c1", "echo", """{"x":1}""")
        val streamer = ScriptedStreamer(listOf(reply("", listOf(call)), reply("answer")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(echo)))

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Completed(2), outcome)
        assertEquals(2, streamer.requests.size)
        assertEquals(listOf("""{"x":1}"""), executed)

        val messages = streamer.requests[1].messages
        assertEquals(2, messages.size)
        assertEquals(ApiMessage(role = "assistant", content = ""), messages[0].copy(toolCalls = null))
        assertEquals(listOf(ApiToolCall("c1", "echo", """{"x":1}""")), messages[0].toolCalls)
        assertEquals("tool", messages[1].role)
        assertEquals("c1", messages[1].toolCallId)
        val payload = decodeToolResult(messages[1].content)!!
        assertEquals(AgentToolStatus.OK, payload.status)
        assertEquals("echo:{\"x\":1}", payload.output)
    }

    @Test
    fun parallelCallsSequential() = runBlocking {
        val order = mutableListOf<String>()
        val first = TestTool("first") {
            order += "first"
            ToolResult(AgentToolStatus.OK, "1")
        }
        val second = TestTool("second") {
            order += "second"
            ToolResult(AgentToolStatus.OK, "2")
        }
        val calls = listOf(
            AssembledToolCall(0, "c0", "first", "{}"),
            AssembledToolCall(1, "c1", "second", "{}"),
        )
        val streamer = ScriptedStreamer(listOf(reply("", calls), reply("done")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(second, first)))

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Completed(2), outcome)
        assertEquals(listOf("first", "second"), order)
        val messages = streamer.requests[1].messages
        assertEquals(3, messages.size)
        assertEquals(listOf("c0", "c1"), messages.drop(1).map { it.toolCallId })
        assertEquals(listOf("tool", "tool"), messages.drop(1).map { it.role })
    }

    @Test
    fun unknownToolContinues() = runBlocking {
        val call = AssembledToolCall(0, "c1", "missing", "{}")
        val streamer = ScriptedStreamer(listOf(reply("", listOf(call)), reply("recovered")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(emptyList()))

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Completed(2), outcome)
        assertEquals(2, streamer.requests.size)
        assertEquals(1, callbacks.finished.size)
        val result = callbacks.finished.single().second
        assertEquals(AgentToolStatus.ERROR, result.status)
        assertTrue(result.output.contains("Unknown tool"))
        val payload = decodeToolResult(streamer.requests[1].messages.last().content)!!
        assertEquals(AgentToolStatus.ERROR, payload.status)
    }

    @Test
    fun malformedArgsContinues() = runBlocking {
        val executed = mutableListOf<String>()
        val echo = TestTool("echo") { args ->
            executed += args
            ToolResult(AgentToolStatus.OK, "ok")
        }
        val call = AssembledToolCall(0, "c1", "echo", "{bad")
        val streamer = ScriptedStreamer(listOf(reply("", listOf(call)), reply("recovered")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(echo)))

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Completed(2), outcome)
        assertTrue(executed.isEmpty())
        val result = callbacks.finished.single().second
        assertEquals(AgentToolStatus.ERROR, result.status)
        assertTrue(result.output.startsWith("Invalid arguments JSON: "))
        val payload = decodeToolResult(streamer.requests[1].messages.last().content)!!
        assertEquals(AgentToolStatus.ERROR, payload.status)
    }

    @Test
    fun toolFailureContinues() = runBlocking {
        val call = AssembledToolCall(0, "c1", "boom", "{}")
        val boom = TestTool("boom") { throw IllegalStateException("boom") }
        val streamer = ScriptedStreamer(listOf(reply("", listOf(call)), reply("recovered")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(boom)))

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Completed(2), outcome)
        assertEquals(2, streamer.requests.size)
        val result = callbacks.finished.single().second
        assertEquals(AgentToolStatus.ERROR, result.status)
        assertEquals("Tool failed: boom", result.output)
        val payload = decodeToolResult(streamer.requests[1].messages.last().content)!!
        assertEquals(AgentToolStatus.ERROR, payload.status)
    }

    @Test
    fun maxIterationsLimit() = runBlocking {
        val executions = mutableListOf<String>()
        val echo = TestTool("echo") { args ->
            executions += args
            ToolResult(AgentToolStatus.OK, "ok")
        }
        val scripts = (0 until 3).map { index ->
            reply("", listOf(AssembledToolCall(0, "c$index", "echo", "{}")))
        }
        val streamer = ScriptedStreamer(scripts)
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(echo)))

        val outcome = loop.run(
            SERVER_URL,
            null,
            SYSTEM_PROMPT,
            emptyList(),
            config = AgentLoopConfig(maxIterations = 3),
            callbacks = callbacks,
        )

        assertEquals(AgentLoopOutcome.LimitReached(3), outcome)
        assertEquals(3, streamer.requests.size)
        assertEquals(2, executions.size)
        assertEquals(listOf(1, 2, 3), callbacks.steps.map { it.first })
        assertEquals(listOf(3, 3, 3), callbacks.steps.map { it.second })
        assertEquals(3, callbacks.saved.size)
        assertEquals(3, callbacks.finished.size)
        val third = callbacks.finished.last().second
        assertEquals(AgentToolStatus.ERROR, third.status)
        assertEquals("Maximum number of steps reached: tool was not executed.", third.output)
        assertEquals(
            callbacks.saved.flatMap { it.toolCalls }.map { it.id },
            callbacks.finished.map { it.first.id },
        )
        callbacks.saved.forEach { reply ->
            assertEquals(
                reply.toolCalls.size,
                callbacks.finished.count { finished -> finished.first in reply.toolCalls },
            )
        }
    }

    @Test
    fun requestsUseToolsAndHistory() = runBlocking {
        val history = listOf(ApiMessage(role = "user", content = "hi"))
        val echo = TestTool("echo") { ToolResult(AgentToolStatus.OK, "ok") }
        val other = TestTool("zeta") { ToolResult(AgentToolStatus.OK, "z") }
        val registry = AgentToolRegistry(listOf(echo, other))
        val call = AssembledToolCall(0, "c1", "echo", """{"x":1}""")
        val streamer = ScriptedStreamer(listOf(reply("", listOf(call)), reply("answer")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, registry)

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, history, callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Completed(2), outcome)
        val first = streamer.requests[0]
        assertEquals(history, first.messages)
        assertEquals(registry.manifest, first.tools)
        assertEquals("auto", first.toolChoice)
        assertEquals(SYSTEM_PROMPT, first.systemPrompt)

        val second = streamer.requests[1]
        assertEquals(
            history +
                assistantApiMessage("", listOf(AgentToolCall("c1", "echo", """{"x":1}"""))),
            second.messages.take(2),
        )
        assertEquals("tool", second.messages[2].role)
        assertEquals("c1", second.messages[2].toolCallId)
        assertEquals(registry.manifest, second.tools)
        assertEquals("auto", second.toolChoice)
    }

    @Test
    fun requestCarriesReasoningBudget() = runBlocking {
        val streamer = ScriptedStreamer(listOf(reply("done")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(emptyList()))
        val config = AgentLoopConfig(
            reasoningBudget = 384,
            reasoningBudgetMessage = "budget",
        )

        val outcome = loop.run(
            SERVER_URL,
            null,
            SYSTEM_PROMPT,
            emptyList(),
            config = config,
            callbacks = callbacks,
        )

        assertEquals(AgentLoopOutcome.Completed(1), outcome)
        assertEquals(384, streamer.requests.single().reasoningBudget)
        assertEquals("budget", streamer.requests.single().reasoningBudgetMessage)
    }

    @Test
    fun contextPreparerRunsEachIterationAndPersists() = runBlocking {
        val history = listOf(
            ApiMessage(role = "user", content = "old"),
            ApiMessage(role = "user", content = "new"),
        )
        val echo = TestTool("echo") { ToolResult(AgentToolStatus.OK, "ok") }
        val call = AssembledToolCall(0, "c1", "echo", "{}")
        val streamer = ScriptedStreamer(listOf(reply("", listOf(call)), reply("answer")))
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(echo)))

        val received = mutableListOf<List<ApiMessage>>()
        val outcome = loop.run(
            SERVER_URL,
            null,
            SYSTEM_PROMPT,
            history,
            contextPreparer = { messages ->
                received += messages
                messages.filter { it.content != "old" }
            },
            callbacks = callbacks,
        )

        assertEquals(AgentLoopOutcome.Completed(2), outcome)
        assertEquals(2, received.size)
        assertEquals(history, received[0])
        assertEquals(listOf(ApiMessage(role = "user", content = "new")), streamer.requests[0].messages)
        assertEquals(
            listOf(ApiMessage(role = "user", content = "new")) +
                assistantApiMessage("", listOf(AgentToolCall("c1", "echo", "{}"))) +
                toolApiMessage(AgentToolCall("c1", "echo", "{}"), ToolResult(AgentToolStatus.OK, "ok")),
            received[1],
        )
        assertEquals(received[1], streamer.requests[1].messages)
    }

    @Test
    fun contextSummaryIsReadEachIterationIntoSystemPrompt() = runBlocking {
        val echo = TestTool("echo") { ToolResult(AgentToolStatus.OK, "ok") }
        val call = AssembledToolCall(0, "c1", "echo", "{}")
        val streamer = ScriptedStreamer(listOf(reply("", listOf(call)), reply("answer")))
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(echo)))
        var summary: String? = null

        loop.run(
            SERVER_URL,
            null,
            SYSTEM_PROMPT,
            listOf(ApiMessage(role = "user", content = "q")),
            contextPreparer = { messages ->
                if (messages.size > 1) summary = "compacted"
                messages
            },
            contextSummary = { summary },
            callbacks = LoopRecordingCallbacks(),
        )

        assertEquals(SYSTEM_PROMPT, streamer.requests[0].systemPrompt)
        assertEquals(withContextSummary(SYSTEM_PROMPT, "compacted"), streamer.requests[1].systemPrompt)
    }

    @Test
    fun identicalCallIsNotExecutedThirdTimeInARow() = runBlocking {
        var executions = 0
        val echo = TestTool("echo") {
            executions++
            ToolResult(AgentToolStatus.OK, "ok")
        }
        val call = AssembledToolCall(0, "c", "echo", """{"x":1}""")
        val streamer = ScriptedStreamer(
            listOf(reply("", listOf(call)), reply("", listOf(call)), reply("", listOf(call)), reply("done")),
        )
        val callbacks = LoopRecordingCallbacks()

        val outcome = AgentLoop(streamer, AgentToolRegistry(listOf(echo))).run(
            SERVER_URL,
            null,
            SYSTEM_PROMPT,
            listOf(ApiMessage(role = "user", content = "q")),
            callbacks = callbacks,
        )

        assertEquals(AgentLoopOutcome.Completed(4), outcome)
        assertEquals(2, executions)
        val lastToolMessage = streamer.requests[3].messages.last { it.role == "tool" }
        assertTrue(lastToolMessage.content.contains("not executed"))
    }

    @Test
    fun contextOverflowRetriesWithPreparedContext() = runBlocking {
        val overflow: ScriptedReply = { _, _, _ ->
            Result.failure(java.io.IOException("HTTP 400: request (5000 tokens) exceeds the available context size (4096 tokens)"))
        }
        val streamer = ScriptedStreamer(listOf(overflow, reply("answer")))
        var overflows = 0
        var preparations = 0

        val outcome = AgentLoop(streamer, AgentToolRegistry(emptyList())).run(
            SERVER_URL,
            null,
            SYSTEM_PROMPT,
            listOf(ApiMessage(role = "user", content = "q")),
            contextPreparer = { messages ->
                preparations++
                messages
            },
            onContextOverflow = { info ->
                assertEquals(ContextOverflow(5000, 4096), info)
                overflows++
                true
            },
            callbacks = LoopRecordingCallbacks(),
        )

        assertEquals(AgentLoopOutcome.Completed(1), outcome)
        assertEquals(1, overflows)
        assertEquals(2, preparations)
    }

    @Test
    fun contextOverflowGivesUpAfterRetryLimit() = runBlocking {
        val overflow: ScriptedReply = { _, _, _ ->
            Result.failure(java.io.IOException("exceed_context_size_error"))
        }
        val streamer = ScriptedStreamer(List(MAX_CONTEXT_OVERFLOW_RETRIES + 1) { overflow })

        val outcome = AgentLoop(streamer, AgentToolRegistry(emptyList())).run(
            SERVER_URL,
            null,
            SYSTEM_PROMPT,
            listOf(ApiMessage(role = "user", content = "q")),
            onContextOverflow = { true },
            callbacks = LoopRecordingCallbacks(),
        )

        assertTrue(outcome is AgentLoopOutcome.Failed)
        assertEquals(MAX_CONTEXT_OVERFLOW_RETRIES + 1, streamer.requests.size)
    }

    @Test
    fun writeContentIsCompactedBeforeNextRequest() = runBlocking {
        val write = TestTool("write") { ToolResult(AgentToolStatus.OK, "Wrote 5 bytes to a.txt") }
        val call = AssembledToolCall(0, "w1", "write", """{"path":"a.txt","content":"hello"}""")
        val streamer = ScriptedStreamer(listOf(reply("", listOf(call)), reply("done")))

        AgentLoop(streamer, AgentToolRegistry(listOf(write))).run(
            SERVER_URL,
            null,
            SYSTEM_PROMPT,
            listOf(ApiMessage(role = "user", content = "q")),
            callbacks = LoopRecordingCallbacks(),
        )

        val sentCall = streamer.requests[1].messages.first { it.role == "assistant" }.toolCalls!!.single()
        assertEquals("""{"path":"a.txt","content":"[5 chars written to a.txt]"}""", sentCall.argumentsJson)
    }

    @Test
    fun callbacksOrder() = runBlocking {
        val echo = TestTool("echo") { ToolResult(AgentToolStatus.OK, "ok") }
        val call = AssembledToolCall(0, "c1", "echo", "{}")
        val streamer = ScriptedStreamer(
            listOf(
                { _, _, onDelta ->
                    onDelta("thinking", false)
                    Result.success(ChatStreamResult("", null, listOf(call)))
                },
                { _, _, onDelta ->
                    onDelta("answer", false)
                    Result.success(ChatStreamResult("answer", null))
                },
            ),
        )
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(listOf(echo)))

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Completed(2), outcome)
        assertEquals(
            listOf(
                "step:1",
                "assistant_start",
                "delta:thinking",
                "assistant_saved",
                "tool_started:echo",
                "tool_finished:echo",
                "step:2",
                "assistant_start",
                "delta:answer",
                "assistant_saved",
            ),
            callbacks.events,
        )
    }

    @Test
    fun failureOutcome() = runBlocking {
        val streamer = ScriptedStreamer(
            listOf({ _, _, _ -> Result.failure<ChatStreamResult>(IOException("boom")) }),
        )
        val callbacks = LoopRecordingCallbacks()
        val loop = AgentLoop(streamer, AgentToolRegistry(emptyList()))

        val outcome = loop.run(SERVER_URL, null, SYSTEM_PROMPT, emptyList(), callbacks = callbacks)

        assertEquals(AgentLoopOutcome.Failed("boom"), outcome)
        assertEquals(1, streamer.requests.size)
        assertTrue(callbacks.saved.isEmpty())
        assertTrue(callbacks.started.isEmpty())
        assertTrue(callbacks.finished.isEmpty())
    }

    private fun reply(
        content: String,
        calls: List<AssembledToolCall> = emptyList(),
    ): ScriptedReply = { _, _, _ -> Result.success(ChatStreamResult(content, null, calls)) }
}

private const val SERVER_URL = "http://127.0.0.1:8080"
private const val SYSTEM_PROMPT = "system"

private typealias ScriptedReply = suspend (
    ChatRequest,
    (ChatEvent.ToolCallDelta) -> Unit,
    (String, Boolean) -> Unit,
) -> Result<ChatStreamResult>

private class ScriptedStreamer(
    private val scripts: List<ScriptedReply>,
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
        val script = scripts.getOrNull(index)
            ?: throw AssertionError("Unexpected stream request #${index + 1}")
        return script(request, onToolCall, onDelta)
    }
}

private class TestTool(
    override val name: String,
    private val block: suspend (String) -> ToolResult,
) : AgentTool {
    override val description: String = "test $name"
    override val parametersJsonSchema: String = """{"type":"object"}"""

    override suspend fun execute(paramsJson: String): ToolResult = block(paramsJson)
}

private data class SavedAssistant(
    val content: String,
    val toolCalls: List<AgentToolCall>,
    val timings: ChatTimings?,
)

private class LoopRecordingCallbacks : AgentLoopCallbacks {

    val events = mutableListOf<String>()
    val steps = mutableListOf<Pair<Int, Int>>()
    val saved = mutableListOf<SavedAssistant>()
    val started = mutableListOf<AgentToolCall>()
    val finished = mutableListOf<Pair<AgentToolCall, ToolResult>>()

    override suspend fun onAssistantStart() {
        events += "assistant_start"
    }

    override fun onTextDelta(text: String, isReasoning: Boolean) {
        events += "delta:$text"
    }

    override fun onToolCallDelta(delta: ChatEvent.ToolCallDelta) = Unit

    override fun onStep(step: Int, maxIterations: Int) {
        steps += step to maxIterations
        events += "step:$step"
    }

    override suspend fun onAssistantSaved(
        content: String,
        toolCalls: List<AgentToolCall>,
        timings: ChatTimings?,
    ) {
        saved += SavedAssistant(content, toolCalls, timings)
        events += "assistant_saved"
    }

    override suspend fun onToolStarted(call: AgentToolCall) {
        started += call
        events += "tool_started:${call.name}"
    }

    override suspend fun onToolFinished(call: AgentToolCall, result: ToolResult) {
        finished += call to result
        events += "tool_finished:${call.name}"
    }
}
