package com.aipaca.android.engine.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessageCodecTest {

    @Test
    fun toolCallsRoundTripWithUnicodeAndQuotes() {
        val calls = listOf(
            AgentToolCall(
                id = "call_1",
                name = "write",
                argsJson = """{"path":"файл \"α\".txt","content":"emoji 😀 \\ slash"}""",
            ),
            AgentToolCall(id = "call_2", name = "read", argsJson = "{}"),
        )

        assertEquals(calls, decodeToolCalls(encodeToolCalls(calls)))
    }

    @Test
    fun decodeToolCallsReturnsEmptyForMissingOrInvalidInput() {
        assertTrue(decodeToolCalls(null).isEmpty())
        assertTrue(decodeToolCalls("").isEmpty())
        assertTrue(decodeToolCalls("   ").isEmpty())
        assertTrue(decodeToolCalls("not json").isEmpty())
        assertTrue(decodeToolCalls("""{"id":"x"}""").isEmpty())
        assertTrue(decodeToolCalls("null").isEmpty())
    }

    @Test
    fun decodeToolCallsSkipsMalformedEntries() {
        val raw = """[
            {"id":"a","name":"read","argsJson":"{\"path\":\"x\"}"},
            "junk",
            42,
            {"id":"b"},
            {"name":"list","argsJson":"{}","extra":true}
        ]"""

        val calls = decodeToolCalls(raw)

        assertEquals(3, calls.size)
        assertEquals(AgentToolCall("a", "read", """{"path":"x"}"""), calls[0])
        assertEquals(AgentToolCall("b", "", ""), calls[1])
        assertEquals(AgentToolCall("", "list", "{}"), calls[2])
    }

    @Test
    fun toolResultRoundTripWithMeta() {
        val result = ToolResult(
            status = AgentToolStatus.OK,
            output = "HTTP 200 OK\n\nbody",
            meta = mapOf("code" to "200", "contentType" to "text/plain"),
        )

        val payload = decodeToolResult(encodeToolResult(result))

        assertEquals(
            AgentToolPayload(
                status = AgentToolStatus.OK,
                output = "HTTP 200 OK\n\nbody",
                meta = mapOf("code" to "200", "contentType" to "text/plain"),
            ),
            payload,
        )
    }

    @Test
    fun capToolOutputLeavesExactlyAtLimitUnchanged() {
        val output = "a".repeat(100)

        assertEquals(output, capToolOutput(output, maxChars = 100))
    }

    @Test
    fun capToolOutputTruncatesOverLimitWithSuffix() {
        val output = "a".repeat(101)

        val capped = capToolOutput(output, maxChars = 100)

        assertEquals("a".repeat(100) + "\n[truncated]", capped)
    }

    @Test
    fun encodeToolResultCapsOutputAndSetsTruncatedPreservingMeta() {
        val output = "x".repeat(TOOL_OUTPUT_MAX_CHARS + 10)
        val result = ToolResult(
            status = AgentToolStatus.OK,
            output = output,
            meta = mapOf("code" to "200"),
        )

        val payload = decodeToolResult(encodeToolResult(result))!!

        assertEquals(
            "x".repeat(TOOL_OUTPUT_MAX_CHARS) + "\n[truncated]",
            payload.output,
        )
        assertEquals("true", payload.meta["truncated"])
        assertEquals("200", payload.meta["code"])
    }

    @Test
    fun encodeToolResultMarksTruncatedWhenFlagSetEvenIfShort() {
        val result = ToolResult(
            status = AgentToolStatus.ERROR,
            output = "short",
            truncated = true,
            meta = mapOf("reason" to "cap"),
        )

        val payload = decodeToolResult(encodeToolResult(result))!!

        assertEquals("short", payload.output)
        assertEquals("true", payload.meta["truncated"])
        assertEquals("cap", payload.meta["reason"])
    }

    @Test
    fun decodeToolResultReturnsNullForInvalidInput() {
        assertNull(decodeToolResult(null))
        assertNull(decodeToolResult(""))
        assertNull(decodeToolResult("not json"))
        assertNull(decodeToolResult("[]"))
        assertNull(decodeToolResult("null"))
    }
}
