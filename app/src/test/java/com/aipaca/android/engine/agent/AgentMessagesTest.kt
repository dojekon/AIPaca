package com.aipaca.android.engine.agent

import com.aipaca.android.data.db.entity.MessageEntity
import com.aipaca.android.engine.chat.ApiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessagesTest {

    private fun message(
        id: Long,
        role: String,
        content: String,
        toolCalls: String? = null,
        toolCallId: String? = null,
    ) = MessageEntity(
        id = id,
        chatId = 1L,
        role = role,
        content = content,
        createdAt = id,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
    )

    @Test
    fun mapsUserAndAssistantInOrder() {
        val input = listOf(
            message(1, "user", "hello"),
            message(2, "assistant", "hi there"),
            message(3, "user", "again"),
        )

        val result = toApiMessages(input)

        assertEquals(listOf("user", "assistant", "user"), result.map { it.role })
        assertEquals(listOf("hello", "hi there", "again"), result.map { it.content })
        assertTrue(result.all { it.toolCalls == null && it.toolCallId == null })
    }

    @Test
    fun mapsAssistantToolCallsWithCompactedWriteContent() {
        val raw = encodeToolCalls(
            listOf(
                AgentToolCall("call_1", "read", """{"path":"a.txt"}"""),
                AgentToolCall("call_2", "write", """{"path":"b.txt","content":"x"}"""),
            ),
        )
        val input = listOf(message(1, "assistant", "working", toolCalls = raw))

        val result = toApiMessages(input)

        assertEquals(1, result.size)
        assertEquals("assistant", result[0].role)
        assertEquals("working", result[0].content)
        assertEquals(
            listOf(
                ApiToolCall("call_1", "read", """{"path":"a.txt"}"""),
                ApiToolCall("call_2", "write", """{"path":"b.txt","content":"[1 chars written to b.txt]"}"""),
            ),
            result[0].toolCalls,
        )
    }

    @Test
    fun keepsEmptyContentWithToolCalls() {
        val raw = encodeToolCalls(listOf(AgentToolCall("call_1", "read", "{}")))
        val input = listOf(message(1, "assistant", "", toolCalls = raw))

        val result = toApiMessages(input)

        assertEquals("", result[0].content)
        assertEquals(1, result[0].toolCalls?.size)
    }

    @Test
    fun mapsToolRowWithToolCallId() {
        val input = listOf(message(1, "tool", "output text", toolCallId = "call_1"))

        val result = toApiMessages(input)

        assertEquals(1, result.size)
        assertEquals("tool", result[0].role)
        assertEquals("output text", result[0].content)
        assertEquals("call_1", result[0].toolCallId)
    }

    @Test
    fun skipsToolRowWithoutToolCallId() {
        val input = listOf(
            message(1, "tool", "orphan", toolCallId = null),
            message(2, "tool", "blank", toolCallId = "  "),
            message(3, "tool", "kept", toolCallId = "call_9"),
        )

        val result = toApiMessages(input)

        assertEquals(1, result.size)
        assertEquals("kept", result[0].content)
        assertEquals("call_9", result[0].toolCallId)
    }

    @Test
    fun mapsSystemRowAsSystem() {
        val input = listOf(message(1, "system", "be nice"))

        val result = toApiMessages(input)

        assertEquals(1, result.size)
        assertEquals("system", result[0].role)
        assertEquals("be nice", result[0].content)
    }

    @Test
    fun malformedToolCallsFallsBackToPlainAssistant() {
        val input = listOf(message(1, "assistant", "text", toolCalls = "not json"))

        val result = toApiMessages(input)

        assertEquals(1, result.size)
        assertEquals("assistant", result[0].role)
        assertEquals("text", result[0].content)
        assertNull(result[0].toolCalls)
    }

    @Test
    fun preservesUnicodeContent() {
        val input = listOf(
            message(1, "user", "привет 😀 α"),
            message(2, "assistant", "ответ \n\"quoted\""),
        )

        val result = toApiMessages(input)

        assertEquals("привет 😀 α", result[0].content)
        assertEquals("ответ \n\"quoted\"", result[1].content)
    }

    @Test
    fun historyWithoutSummaryIsKeptWhole() {
        val input = listOf(message(1, "user", "a"), message(2, "assistant", "b"))

        val history = agentHistory(input)

        assertNull(history.summary)
        assertEquals(input, history.messages)
    }

    @Test
    fun historyStartsAfterLastSummary() {
        val input = listOf(
            message(1, "user", "old"),
            message(2, "system", "first summary"),
            message(3, "user", "middle"),
            message(4, "system", "second summary"),
            message(5, "user", "new"),
            message(6, "assistant", "answer"),
        )

        val history = agentHistory(input)

        assertEquals("second summary", history.summary)
        assertEquals(listOf(5L, 6L), history.messages.map { it.id })
    }

    @Test
    fun blankSummaryIsTreatedAsMissing() {
        val history = agentHistory(listOf(message(1, "system", " "), message(2, "user", "q")))

        assertNull(history.summary)
        assertEquals(listOf(2L), history.messages.map { it.id })
    }
}
