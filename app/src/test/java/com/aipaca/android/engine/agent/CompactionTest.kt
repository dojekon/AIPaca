package com.aipaca.android.engine.agent

import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ApiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionTest {

    @Test
    fun compactsOnlyWhenOverBudgetAndEnoughMessages() {
        assertTrue(shouldCompact(estimatedTokens = 1001, budgetTokens = 1000, messageCount = 6))
        assertFalse(shouldCompact(estimatedTokens = 1000, budgetTokens = 1000, messageCount = 6))
        assertFalse(shouldCompact(estimatedTokens = 999, budgetTokens = 1000, messageCount = 6))
        assertFalse(shouldCompact(estimatedTokens = 5000, budgetTokens = 1000, messageCount = 5))
        assertTrue(shouldCompact(estimatedTokens = 1001, budgetTokens = 1000, messageCount = 7))
    }

    @Test
    fun minMessagesBoundaryIsInclusive() {
        assertFalse(shouldCompact(2000, 1000, messageCount = COMPACTION_MIN_MESSAGES - 1))
        assertTrue(shouldCompact(2000, 1000, messageCount = COMPACTION_MIN_MESSAGES))
    }

    @Test
    fun customMinMessagesOverridesDefault() {
        assertFalse(shouldCompact(2000, 1000, messageCount = 9, minMessages = 10))
        assertTrue(shouldCompact(2000, 1000, messageCount = 10, minMessages = 10))
    }

    @Test
    fun tinySessionNeverCompacts() {
        assertFalse(shouldCompact(Int.MAX_VALUE, 1, messageCount = 5))
        assertFalse(shouldCompact(100_000, 128, messageCount = 0))
    }

    @Test
    fun requestShapeIsSingleUserMessageWithoutTools() {
        val request = buildCompactionRequest(
            listOf(user("hello"), assistant("hi there")),
        )

        assertEquals(1, request.messages.size)
        assertEquals("user", request.messages.single().role)
        assertTrue(request.systemPrompt!!.isNotEmpty())
        assertEquals(0.0, request.temperature, 0.0)
        assertEquals(700, request.maxTokens)
        assertTrue(request.tools.isEmpty())
    }

    @Test
    fun transcriptContainsRolesToolCallsAndToolCallId() {
        val messages = listOf(
            user("list files"),
            assistant(
                "working",
                listOf(ApiToolCall(id = "call_1", name = "list", argumentsJson = """{"path":"."}""")),
            ),
            tool("call_1", """{"status":"ok"}"""),
            assistant("done"),
        )

        val content = buildCompactionRequest(messages).messages.single().content

        assertTrue(content.contains("user: list files"))
        assertTrue(content.contains("assistant: working"))
        assertTrue(content.contains("""[tool calls: list({"path":"."})]"""))
        assertTrue(content.contains("call_1"))
        assertTrue(content.contains("""{"status":"ok"}"""))
        assertTrue(content.contains("assistant: done"))
    }

    @Test
    fun assistantWithoutToolCallsHasNoToolCallSuffix() {
        val content = buildCompactionRequest(listOf(assistant("plain"))).messages.single().content
        assertEquals("assistant: plain", content)
    }

    @Test
    fun multipleToolCallsJoinedInOrder() {
        val messages = listOf(
            assistant(
                "",
                listOf(
                    ApiToolCall(id = "a", name = "read", argumentsJson = """{"path":"a"}"""),
                    ApiToolCall(id = "b", name = "write", argumentsJson = """{"path":"b"}"""),
                ),
            ),
        )

        val content = buildCompactionRequest(messages).messages.single().content

        assertTrue(
            content.endsWith(
                """[tool calls: read({"path":"a"}), write({"path":"b"})]""",
            ),
        )
    }

    @Test
    fun longTranscriptTruncatedWithMarkerAndLengthBound() {
        val heavy = buildList {
            repeat(200) { add(user("message $it " + "x".repeat(200))) }
        }

        val request = buildCompactionRequest(heavy, maxTranscriptChars = 1000)
        val content = request.messages.single().content

        assertTrue(content.contains(COMPACTION_OMITTED_MARKER))
        assertTrue(content.length <= 1000 + COMPACTION_OMITTED_MARKER.length)
        assertTrue(content.startsWith("user: message 0"))
        assertTrue(content.endsWith("x"))
        assertFalse(content.contains("message 100 "))
    }

    @Test
    fun shortTranscriptIsNotTruncated() {
        val content = buildCompactionRequest(listOf(user("short"))).messages.single().content
        assertFalse(content.contains(COMPACTION_OMITTED_MARKER))
        assertEquals("user: short", content)
    }

    @Test
    fun tinyMaxTranscriptCharsIsCoercedToMinimum() {
        val heavy = List(50) { user("line $it " + "y".repeat(50)) }

        val request = buildCompactionRequest(heavy, maxTranscriptChars = 1)
        val content = request.messages.single().content

        assertTrue(content.contains(COMPACTION_OMITTED_MARKER))
        assertTrue(content.length <= 200 + COMPACTION_OMITTED_MARKER.length)
    }

    @Test
    fun unicodeContentIsPreserved() {
        val messages = listOf(
            user("привет мир"),
            assistant("ответ 日本語"),
            tool("call_1", "результат"),
        )

        val content = buildCompactionRequest(messages).messages.single().content

        assertTrue(content.contains("привет мир"))
        assertTrue(content.contains("ответ 日本語"))
        assertTrue(content.contains("результат"))
    }

    @Test
    fun unicodeTruncationDoesNotSplitSurrogatePairs() {
        val message = user("😀".repeat(5000))

        val content = buildCompactionRequest(listOf(message), maxTranscriptChars = 400).messages.single().content

        assertTrue(content.contains(COMPACTION_OMITTED_MARKER))
        content.forEachIndexed { index, char ->
            if (char.isHighSurrogate()) {
                assertTrue(index + 1 < content.length && content[index + 1].isLowSurrogate())
            }
        }
    }

    @Test
    fun sanitizeRejectsNullAndBlank() {
        assertNull(sanitizeCompactionSummary(null))
        assertNull(sanitizeCompactionSummary(""))
        assertNull(sanitizeCompactionSummary("   \n\t "))
    }

    @Test
    fun sanitizeTrimsWhitespace() {
        assertEquals("hello", sanitizeCompactionSummary("  hello \n"))
    }

    @Test
    fun sanitizeStripsLeadingSummaryPrefix() {
        assertEquals("the goal is X", sanitizeCompactionSummary("Summary: the goal is X"))
        assertEquals("the goal is X", sanitizeCompactionSummary("summary: the goal is X"))
        assertEquals("the goal is X", sanitizeCompactionSummary("SUMMARY:   the goal is X"))
    }

    @Test
    fun sanitizePrefixOnlyBecomesNull() {
        assertNull(sanitizeCompactionSummary("Summary:"))
        assertNull(sanitizeCompactionSummary("Summary:   "))
    }

    @Test
    fun sanitizeKeepsSummaryThatStartsWithWordSummary() {
        assertEquals("Summary of events", sanitizeCompactionSummary("Summary of events"))
    }

    @Test
    fun sanitizeClampsVeryLongText() {
        val raw = "z".repeat(COMPACTION_MAX_SUMMARY_CHARS + 5000)

        val sanitized = sanitizeCompactionSummary(raw)

        assertEquals(COMPACTION_MAX_SUMMARY_CHARS, sanitized!!.length)
    }

    @Test
    fun sanitizeClampsLongTextAfterStrippingPrefix() {
        val raw = "Summary: " + "z".repeat(COMPACTION_MAX_SUMMARY_CHARS + 10)

        val sanitized = sanitizeCompactionSummary(raw)

        assertEquals(COMPACTION_MAX_SUMMARY_CHARS, sanitized!!.length)
        assertFalse(sanitized.startsWith("Summary:"))
    }

    @Test
    fun splitKeepsActiveTurnFromLastUserMessage() {
        val messages = listOf(
            user("first"),
            assistant("reply"),
            user("current"),
            assistant("", listOf(ApiToolCall("c1", "read", "{}"))),
            tool("c1", "file"),
        )

        val split = splitForCompaction(messages)

        assertEquals(messages.take(2), split.older)
        assertEquals(messages.drop(2), split.activeTurn)
    }

    @Test
    fun splitWithSingleTurnHasNothingToCompact() {
        val messages = listOf(user("only"), assistant("", listOf(ApiToolCall("c1", "read", "{}"))), tool("c1", "x"))

        val split = splitForCompaction(messages)

        assertTrue(split.older.isEmpty())
        assertEquals(messages, split.activeTurn)
    }

    @Test
    fun compactionInputPrependsPreviousSummary() {
        val messages = listOf(user("q"))

        assertEquals(messages, compactionInput(null, messages))
        assertEquals(
            listOf(ApiMessage(role = "system", content = "earlier")) + messages,
            compactionInput("earlier", messages),
        )
    }

    @Test
    fun summaryIsAppendedToSystemPrompt() {
        assertEquals("base", withContextSummary("base", null))
        assertEquals("base", withContextSummary("base", "  "))
        assertEquals("base\n\n$CONTEXT_SUMMARY_HEADER\nnotes", withContextSummary("base", "notes"))
    }

    private fun user(content: String) = ApiMessage(role = "user", content = content)

    private fun assistant(content: String, toolCalls: List<ApiToolCall>? = null) =
        ApiMessage(role = "assistant", content = content, toolCalls = toolCalls)

    private fun tool(toolCallId: String, content: String) =
        ApiMessage(role = "tool", content = content, toolCallId = toolCallId)

    @Test
    fun compactionRequestDisablesModelThinking() {
        val request = buildCompactionRequest(listOf(user("hello"), assistant("hi")))

        assertEquals(false, request.enableThinking)
    }
}
