package com.aipaca.android.engine.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClientTest {

    private val json = Json

    private fun body(request: ChatRequest): JsonObject =
        json.parseToJsonElement(ChatClient().buildRequestBody(request, stream = true)).jsonObject

    private fun request(
        messages: List<ApiMessage>,
        tools: List<ToolDef> = emptyList(),
        toolChoice: String? = null,
    ): ChatRequest = ChatRequest(
        messages = messages,
        systemPrompt = null,
        tools = tools,
        toolChoice = toolChoice,
    )

    @Test
    fun assistantToolCallsAreSerialized() {
        val request = request(
            listOf(
                ApiMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(ApiToolCall("call_1", "read", "{\"path\":\"a\"}")),
                ),
            ),
        )

        val message = body(request)["messages"]!!.jsonArray[0].jsonObject
        val call = message["tool_calls"]!!.jsonArray[0].jsonObject
        val function = call["function"]!!.jsonObject
        val arguments = function["arguments"] as JsonPrimitive

        assertEquals("call_1", (call["id"] as JsonPrimitive).content)
        assertEquals("function", (call["type"] as JsonPrimitive).content)
        assertEquals("read", (function["name"] as JsonPrimitive).content)
        assertTrue(arguments.isString)
        assertEquals("{\"path\":\"a\"}", arguments.content)
    }

    @Test
    fun toolMessageSerializesToolCallId() {
        val request = request(
            listOf(ApiMessage(role = "tool", content = "ok", toolCallId = "call_7")),
        )

        val message = body(request)["messages"]!!.jsonArray[0].jsonObject

        assertEquals("call_7", (message["tool_call_id"] as JsonPrimitive).content)
    }

    @Test
    fun toolsArrayEmbedsParametersObjectAndAutoChoice() {
        val schema = """{"type":"object","properties":{"path":{"type":"string"}}}"""
        val request = request(
            listOf(ApiMessage("user", "hi")),
            tools = listOf(ToolDef("read", "Read a file", schema)),
        )

        val root = body(request)
        val tool = root["tools"]!!.jsonArray[0].jsonObject
        val function = tool["function"]!!.jsonObject
        val parameters = function["parameters"]

        assertEquals("function", (tool["type"] as JsonPrimitive).content)
        assertEquals("read", (function["name"] as JsonPrimitive).content)
        assertEquals("Read a file", (function["description"] as JsonPrimitive).content)
        assertTrue(parameters is JsonObject)
        assertEquals("object", (parameters!!.jsonObject["type"] as JsonPrimitive).content)
        assertEquals("auto", (root["tool_choice"] as JsonPrimitive).content)
    }

    @Test
    fun explicitToolChoiceIsUsed() {
        val request = request(
            listOf(ApiMessage("user", "hi")),
            tools = listOf(ToolDef("read", "Read a file", "{}")),
            toolChoice = "required",
        )

        assertEquals("required", (body(request)["tool_choice"] as JsonPrimitive).content)
    }

    @Test
    fun withoutToolsNoToolsOrChoiceKeys() {
        val root = body(request(listOf(ApiMessage("user", "hi"))))

        assertFalse(root.containsKey("tools"))
        assertFalse(root.containsKey("tool_choice"))
    }

    @Test
    fun invalidSchemaFallsBackToEmptyObject() {
        val request = request(
            listOf(ApiMessage("user", "hi")),
            tools = listOf(ToolDef("broken", "desc", "{not valid json")),
        )

        val root = body(request)
        val parameters = root["tools"]!!.jsonArray[0].jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject

        assertTrue(parameters.isEmpty())
    }

    @Test
    fun unicodeAndQuotesInContentArePreserved() {
        val content = "Привет \"мир\" \\ 日本語\nline\ttab"
        val request = request(listOf(ApiMessage("user", content)))

        val message = body(request)["messages"]!!.jsonArray[0].jsonObject
        val serialized = (message["content"] as JsonPrimitive).content

        assertEquals(content, serialized)
    }

    @Test
    fun chatTemplateKwargsCarryThinkingFlag() {
        val request = request(listOf(ApiMessage("user", "hi"))).copy(enableThinking = false)

        val kwargs = body(request)["chat_template_kwargs"]!!.jsonObject
        assertEquals("false", (kwargs["enable_thinking"] as JsonPrimitive).content)
    }

    @Test
    fun noThinkingFlagOmitsChatTemplateKwargs() {
        val root = body(request(listOf(ApiMessage("user", "hi"))))

        assertFalse(root.containsKey("chat_template_kwargs"))
    }

    @Test
    fun reasoningBudgetAndMessageAreSerialized() {
        val request = request(listOf(ApiMessage("user", "hi"))).copy(
            reasoningBudget = 512,
            reasoningBudgetMessage = "stop",
        )

        val root = body(request)

        assertEquals("512", (root["reasoning_budget_tokens"] as JsonPrimitive).content)
        assertEquals("stop", (root["reasoning_budget_message"] as JsonPrimitive).content)
    }

    @Test
    fun reasoningBudgetOmitsMessageWhenBlank() {
        val request = request(listOf(ApiMessage("user", "hi"))).copy(
            reasoningBudget = 256,
            reasoningBudgetMessage = "   ",
        )

        val root = body(request)

        assertEquals("256", (root["reasoning_budget_tokens"] as JsonPrimitive).content)
        assertFalse(root.containsKey("reasoning_budget_message"))
    }

    @Test
    fun absentReasoningBudgetOmitsKeys() {
        val root = body(request(listOf(ApiMessage("user", "hi"))))

        assertFalse(root.containsKey("reasoning_budget_tokens"))
        assertFalse(root.containsKey("reasoning_budget_message"))
    }

    @Test
    fun zeroReasoningBudgetEmitsZero() {
        val request = request(listOf(ApiMessage("user", "hi"))).copy(
            reasoningBudget = 0,
            reasoningBudgetMessage = "stop",
        )

        val root = body(request)

        assertEquals("0", (root["reasoning_budget_tokens"] as JsonPrimitive).content)
        assertEquals("stop", (root["reasoning_budget_message"] as JsonPrimitive).content)
    }
}
