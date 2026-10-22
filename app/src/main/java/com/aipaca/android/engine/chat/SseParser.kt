package com.aipaca.android.engine.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

sealed interface ChatEvent {
    data class Delta(val text: String, val isReasoning: Boolean = false) : ChatEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argsChunk: String = "",
    ) : ChatEvent
    data class Done(val timings: ChatTimings?) : ChatEvent
    data object Ignored : ChatEvent
}

private val json = Json
private const val DONE_MARKER = "[DONE]"

fun parseSsePayload(data: String): ChatEvent {
    val payload = data.trim()
    if (payload.isEmpty()) return ChatEvent.Ignored
    if (payload == DONE_MARKER) return ChatEvent.Done(null)

    val root = try {
        json.parseToJsonElement(payload)
    } catch (_: Exception) {
        return ChatEvent.Ignored
    }
    val chunk = root as? JsonObject ?: return ChatEvent.Ignored

    val content = chunk.deltaText("content")
    if (!content.isNullOrEmpty()) return ChatEvent.Delta(content)

    val reasoning = chunk.deltaText("reasoning_content")
    if (!reasoning.isNullOrEmpty()) return ChatEvent.Delta(reasoning, isReasoning = true)

    chunk.toolCallDeltaOrNull()?.let { return it }

    chunk.timingsOrNull()?.let { return ChatEvent.Done(it) }

    if (chunk.finishReason() == "tool_calls") return ChatEvent.Done(null)

    return ChatEvent.Ignored
}

private fun JsonObject.deltaText(field: String): String? {
    val choices = this["choices"] as? JsonArray ?: return null
    val choice = choices.firstOrNull() as? JsonObject ?: return null
    val delta = choice["delta"] as? JsonObject ?: return null
    val content = delta[field] as? JsonPrimitive ?: return null
    if (!content.isString) return null
    return content.content
}

private fun JsonObject.toolCallDeltaOrNull(): ChatEvent.ToolCallDelta? {
    val delta = firstDelta() ?: return null
    val toolCalls = delta["tool_calls"] as? JsonArray ?: return null
    val call = toolCalls.firstOrNull() as? JsonObject ?: return null
    val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: 0
    val id = (call["id"] as? JsonPrimitive)?.stringContentOrNull()
    val function = call["function"] as? JsonObject
    val name = (function?.get("name") as? JsonPrimitive)?.stringContentOrNull()
    val argsChunk = (function?.get("arguments") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    return ChatEvent.ToolCallDelta(index = index, id = id, name = name, argsChunk = argsChunk)
}

private fun JsonObject.finishReason(): String? {
    val choice = firstChoice() ?: return null
    return (choice["finish_reason"] as? JsonPrimitive)?.stringContentOrNull()
}

private fun JsonObject.firstChoice(): JsonObject? {
    val choices = this["choices"] as? JsonArray ?: return null
    return choices.firstOrNull() as? JsonObject
}

private fun JsonObject.firstDelta(): JsonObject? {
    val choice = firstChoice() ?: return null
    return choice["delta"] as? JsonObject
}

private fun JsonPrimitive.stringContentOrNull(): String? {
    if (!isString) return null
    return content.takeIf { it.isNotEmpty() }
}

private fun JsonObject.timingsOrNull(): ChatTimings? {
    val timings = this["timings"] as? JsonObject ?: return null
    return ChatTimings(
        predictedPerSecond = (timings["predicted_per_second"] as? JsonPrimitive)?.doubleOrNull,
        promptPerSecond = (timings["prompt_per_second"] as? JsonPrimitive)?.doubleOrNull,
        predictedN = (timings["predicted_n"] as? JsonPrimitive)?.intOrNull,
        promptN = (timings["prompt_n"] as? JsonPrimitive)?.intOrNull,
    )
}
