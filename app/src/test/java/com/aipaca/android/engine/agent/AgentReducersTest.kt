package com.aipaca.android.engine.agent

import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ApiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReducersTest {

    @Test
    fun stepsGuardedAllowsStepsBelowLimitOnly() {
        assertTrue(stepsGuarded(0, 5))
        assertTrue(stepsGuarded(4, 5))
        assertFalse(stepsGuarded(5, 5))
        assertFalse(stepsGuarded(6, 5))
    }

    @Test
    fun shouldContinueRequiresCallsAndRemainingSteps() {
        val call = AgentToolCall("c1", "echo", "{}")

        assertFalse(shouldContinue(emptyList(), 0, 5))
        assertTrue(shouldContinue(listOf(call), 0, 5))
        assertTrue(shouldContinue(listOf(call), 4, 5))
        assertFalse(shouldContinue(listOf(call), 5, 5))
        assertFalse(shouldContinue(emptyList(), 5, 5))
    }

    @Test
    fun assistantApiMessageOmitsToolCallsWhenEmpty() {
        val message = assistantApiMessage("hello", emptyList())

        assertEquals(ApiMessage(role = "assistant", content = "hello"), message)
        assertNull(message.toolCalls)
    }

    @Test
    fun assistantApiMessageMapsToolCalls() {
        val calls = listOf(
            AgentToolCall("c1", "echo", """{"x":1}"""),
            AgentToolCall("c2", "read", """{"path":"a"}"""),
        )

        val message = assistantApiMessage("working", calls)

        assertEquals("assistant", message.role)
        assertEquals("working", message.content)
        assertEquals(
            listOf(
                ApiToolCall("c1", "echo", """{"x":1}"""),
                ApiToolCall("c2", "read", """{"path":"a"}"""),
            ),
            message.toolCalls,
        )
    }

    @Test
    fun toolApiMessageEncodesResultAndReferencesCall() {
        val call = AgentToolCall("c1", "echo", "{}")
        val result = ToolResult(AgentToolStatus.OK, "hello", meta = mapOf("k" to "v"))

        val message = toolApiMessage(call, result)
        val payload = decodeToolResult(message.content)!!

        assertEquals("tool", message.role)
        assertEquals("c1", message.toolCallId)
        assertNull(message.toolCalls)
        assertEquals(AgentToolStatus.OK, payload.status)
        assertEquals("hello", payload.output)
        assertEquals("v", payload.meta["k"])
    }

    @Test
    fun signatureIgnoresArgumentWhitespace() {
        val compact = AgentToolCall("a", "read", """{"path":"x.txt"}""")
        val spaced = AgentToolCall("b", "read", """{ "path" : "x.txt" }""")

        assertEquals(toolCallSignature(compact), toolCallSignature(spaced))
    }

    @Test
    fun signatureFallsBackToRawArgsWhenMalformed() {
        assertEquals("read:{oops", toolCallSignature(AgentToolCall("a", "read", " {oops ")))
    }

    @Test
    fun repeatedCallDetectedAfterLimitMinusOneIdenticalCalls() {
        assertFalse(isRepeatedCall(emptyList(), "s"))
        assertFalse(isRepeatedCall(listOf("s"), "s"))
        assertTrue(isRepeatedCall(listOf("s", "s"), "s"))
        assertFalse(isRepeatedCall(listOf("s", "t"), "s"))
        assertTrue(isRepeatedCall(listOf("t", "s", "s"), "s"))
    }

    @Test
    fun contextOverflowParsedFromServerMessage() {
        val message = "HTTP 400: {\"error\":{\"code\":400,\"message\":\"request (5000 tokens) exceeds the " +
            "available context size (4096 tokens), try increasing it\",\"type\":\"exceed_context_size_error\"}}"

        assertEquals(ContextOverflow(5000, 4096), parseContextOverflow(message))
    }

    @Test
    fun contextOverflowTypeWithoutNumbers() {
        assertEquals(ContextOverflow(null, null), parseContextOverflow("type exceed_context_size_error"))
        assertNull(parseContextOverflow("HTTP 500: boom"))
        assertNull(parseContextOverflow(null))
    }

    @Test
    fun budgetScaleShrinksByOverflowRatio() {
        assertEquals(0.85 * 4096 / 5000, shrunkBudgetScale(1.0, ContextOverflow(5000, 4096)), 1e-9)
        assertEquals(0.7 * 0.85, shrunkBudgetScale(1.0, ContextOverflow(null, null)), 1e-9)
        assertEquals(0.1, shrunkBudgetScale(0.1, ContextOverflow(null, null)), 1e-9)
    }

    @Test
    fun toolResultIsCappedAndMarkedTruncated() {
        val result = ToolResult(AgentToolStatus.OK, "x".repeat(50)).cappedTo(10)

        assertEquals("x".repeat(10) + "\n[truncated]", result.output)
        assertTrue(result.truncated)
        assertEquals(ToolResult(AgentToolStatus.OK, "short"), ToolResult(AgentToolStatus.OK, "short").cappedTo(10))
    }

    @Test
    fun writeContentIsReplacedWithNote() {
        val args = """{"path":"index.html","content":"<html>hi</html>"}"""

        val compacted = compactToolCallArgs("write", args)

        assertEquals("""{"path":"index.html","content":"[15 chars written to index.html]"}""", compacted)
    }

    @Test
    fun otherToolsAndMalformedArgsAreUntouched() {
        assertEquals("""{"path":"a.txt"}""", compactToolCallArgs("read", """{"path":"a.txt"}"""))
        assertEquals("{oops", compactToolCallArgs("write", "{oops"))
        assertEquals("""{"path":"a.txt"}""", compactToolCallArgs("write", """{"path":"a.txt"}"""))
    }

    @Test
    fun executedCallsAreCompactedInAssistantMessage() {
        val message = ApiMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(ApiToolCall("c1", "write", """{"path":"a.txt","content":"abc"}""")),
        )

        val compacted = compactExecutedCalls(message)

        assertEquals("""{"path":"a.txt","content":"[3 chars written to a.txt]"}""", compacted.toolCalls!!.single().argumentsJson)
    }
}
