package com.aipaca.android.engine.agent

import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ApiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimTest {

    @Test
    fun underBudgetReturnsIdenticalList() {
        val messages = listOf(system("sys"), user("hello"), assistant("hi"))
        val total = totalTokens(messages)

        val result = trimHistory(messages, total)

        assertEquals(messages, result.messages)
        assertFalse(result.trimmed)
        assertEquals(0, result.droppedToolResults)
        assertEquals(0, result.droppedMessages)
        assertEquals(total, result.estimatedTokens)
    }

    @Test
    fun firstPassReplacesOnlyOldestToolResults() {
        val first = "a".repeat(4000)
        val second = "b".repeat(4000)
        val messages = listOf(
            system("sys"),
            user("go"),
            assistant("", listOf(call("c1"))),
            tool("c1", first),
            tool("c1", second),
            assistant("done"),
        )
        val total = totalTokens(messages)

        val result = trimHistory(messages, total - 500)

        assertEquals(1, result.droppedToolResults)
        assertEquals(messages.size, result.messages.size)
        assertEquals(DROPPED_CONTEXT_MARKER, result.messages[3].content)
        assertEquals(second, result.messages[4].content)
        assertEquals("c1", result.messages[3].toolCallId)
        assertTrue(result.trimmed)
    }

    @Test
    fun multipleHeavyToolResultsAreReplacedOldestFirst() {
        val first = "a".repeat(4000)
        val second = "b".repeat(4000)
        val third = "c".repeat(4000)
        val messages = listOf(
            system("sys"),
            user("go"),
            assistant("", listOf(call("c1"))),
            tool("c1", first),
            tool("c2", second),
            tool("c3", third),
            assistant("done"),
        )
        val total = totalTokens(messages)

        val result = trimHistory(messages, total - 1300)

        assertEquals(2, result.droppedToolResults)
        assertEquals(DROPPED_CONTEXT_MARKER, result.messages[3].content)
        assertEquals(DROPPED_CONTEXT_MARKER, result.messages[4].content)
        assertEquals(third, result.messages[5].content)
    }

    @Test
    fun middleTurnsDroppedKeepingFirstAndLastExchanges() {
        val messages = sixTurns()
        val total = totalTokens(messages)

        val result = trimHistory(messages, total - 250)

        assertEquals(listOf("u0", "u3", "u4", "u5"), userLabels(result.messages))
        assertEquals(4, result.droppedMessages)
        assertEquals(listOf("s0"), result.messages.filter { it.role == "system" }.map { it.content })
    }

    @Test
    fun keepLastExchangesRespected() {
        val messages = sixTurns()
        val total = totalTokens(messages)

        val result = trimHistory(messages, total - 600, keepLastExchanges = 2)

        assertEquals(listOf("u0", "u4", "u5"), userLabels(result.messages))
        assertEquals(6, result.droppedMessages)
    }

    @Test
    fun systemMessagesPreservedInOrder() {
        val heavy = "x".repeat(400)
        val messages = listOf(
            system("s0"),
            user("u0 $heavy"),
            assistant("a0 $heavy"),
            user("u1 $heavy"),
            assistant("a1 $heavy"),
            system("summary"),
            user("u2 $heavy"),
            assistant("a2 $heavy"),
            system("s2"),
            user("u3 $heavy"),
            assistant("a3 $heavy"),
            user("u4 $heavy"),
            assistant("a4 $heavy"),
        )
        val total = totalTokens(messages)

        val result = trimHistory(messages, total - 600, keepLastExchanges = 1)

        assertEquals(listOf("s0", "summary", "s2"), result.messages.filter { it.role == "system" }.map { it.content })
        assertEquals(listOf("u0", "u4"), userLabels(result.messages))
        assertEquals(messages.filter { it.role == "system" }, result.messages.filter { it.role == "system" })
    }

    @Test
    fun assistantToolCallsStayWithToolRows() {
        val messages = listOf(
            user("start"),
            assistant("ok"),
            user("work"),
            assistant("a".repeat(4000), listOf(call("c1"))),
            tool("c1", "x".repeat(4000)),
            assistant("done"),
            user("next"),
            assistant("fine"),
            user("last"),
            assistant("", listOf(call("c2"))),
            tool("c2", "result"),
            assistant("bye"),
        )
        val total = totalTokens(messages)

        val result = trimHistory(messages, total - 1300, keepLastExchanges = 1)

        assertTrue(result.messages.none { it.toolCallId == "c1" })
        assertTrue(result.messages.none { message -> message.toolCalls.orEmpty().any { it.id == "c1" } })
        val callIndex = result.messages.indexOfFirst { message -> message.toolCalls.orEmpty().any { it.id == "c2" } }
        assertTrue(callIndex >= 0)
        assertEquals(result.messages[callIndex + 1].toolCallId, "c2")
        assertEquals(listOf("start", "next", "last"), userLabels(result.messages))
    }

    @Test
    fun noUserMessagesKeepsSystemAndLastNonSystemMessages() {
        val messages = listOf(
            system("s0"),
            assistant("a1"),
            tool("t1", "r1"),
            system("s1"),
            assistant("a2"),
            tool("t2", "r2"),
            assistant("a3"),
            tool("t3", "r3"),
        )

        val result = trimHistory(messages, 0, keepLastExchanges = 2)

        assertEquals(
            listOf("system", "system", "assistant", "tool", "assistant", "tool"),
            result.messages.map { it.role },
        )
        assertEquals(listOf("s0", "s1"), result.messages.filter { it.role == "system" }.map { it.content })
        assertTrue(result.messages.none { it.content == "r1" })
        assertEquals(listOf("t2", "t3"), result.messages.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals(2, result.droppedMessages)
    }

    @Test
    fun emptyListAndZeroBudget() {
        val empty = trimHistory(emptyList(), 0)
        assertTrue(empty.messages.isEmpty())
        assertFalse(empty.trimmed)
        assertEquals(0, empty.estimatedTokens)

        val messages = listOf(user("hi"), assistant("hello"))
        val zero = trimHistory(messages, 0)
        assertEquals(messages, zero.messages)
        assertEquals(totalTokens(messages), zero.estimatedTokens)
        assertFalse(zero.trimmed)
    }

    @Test
    fun estimatedTokensDecreasesAndMatchesRecalculation() {
        val messages = sixTurns()
        val total = totalTokens(messages)

        val result = trimHistory(messages, total - 250)

        assertEquals(totalTokens(result.messages), result.estimatedTokens)
        assertTrue(result.estimatedTokens < total)
        assertTrue(result.trimmed)
    }

    private fun sixTurns(): List<ApiMessage> {
        val heavy = "x".repeat(400)
        return listOf(system("s0")) + (0 until 6).flatMap { index ->
            listOf(
                user("u$index $heavy"),
                assistant("a$index $heavy"),
            )
        }
    }

    private fun userLabels(messages: List<ApiMessage>): List<String> =
        messages.filter { it.role == "user" }.map { it.content.substringBefore(' ') }

    private fun totalTokens(messages: List<ApiMessage>): Int = messages.sumOf { estimateMessageTokens(it) }

    private fun system(content: String) = ApiMessage(role = "system", content = content)

    private fun user(content: String) = ApiMessage(role = "user", content = content)

    private fun assistant(content: String, toolCalls: List<ApiToolCall>? = null) =
        ApiMessage(role = "assistant", content = content, toolCalls = toolCalls)

    private fun tool(toolCallId: String, content: String) =
        ApiMessage(role = "tool", content = content, toolCallId = toolCallId)

    private fun call(id: String) = ApiToolCall(id = id, name = "test", argumentsJson = "{}")

    @Test
    fun hysteresisKeepsHistoryUntouchedWithinBudget() {
        val messages = listOf(ApiMessage("user", "a".repeat(400)), ApiMessage("assistant", "b".repeat(400)))
        val total = messages.sumOf { estimateMessageTokens(it) }

        val result = trimWithHysteresis(messages, total)

        assertEquals(messages, result.messages)
        assertFalse(result.trimmed)
    }

    @Test
    fun hysteresisTrimsBelowTargetSoNextTurnsOnlyAppend() {
        val messages = (1..12).flatMap { turn ->
            listOf(ApiMessage("user", "question $turn " + "q".repeat(400)), ApiMessage("assistant", "a".repeat(400)))
        }
        val budget = messages.sumOf { estimateMessageTokens(it) } - 50

        val result = trimWithHysteresis(messages, budget)

        assertTrue(result.trimmed)
        assertTrue(result.estimatedTokens <= (budget * TRIM_TARGET_RATIO).toInt())
        val next = result.messages + ApiMessage("user", "q".repeat(400)) + ApiMessage("assistant", "a".repeat(400))
        assertFalse(trimWithHysteresis(next, budget).trimmed)
    }
}
