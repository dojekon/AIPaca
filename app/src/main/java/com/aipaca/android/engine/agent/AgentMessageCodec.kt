package com.aipaca.android.engine.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val TOOL_OUTPUT_MAX_CHARS = 16 * 1024

data class AgentToolCall(val id: String, val name: String, val argsJson: String)

data class AgentToolPayload(
    val status: String,
    val output: String,
    val meta: Map<String, String> = emptyMap(),
)

fun encodeToolCalls(calls: List<AgentToolCall>): String =
    buildJsonArray {
        calls.forEach { call ->
            add(
                buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    put("argsJson", call.argsJson)
                },
            )
        }
    }.toString()

fun decodeToolCalls(raw: String?): List<AgentToolCall> {
    val array = raw?.let(::parseJsonElement) as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        AgentToolCall(
            id = obj.stringOrEmpty("id"),
            name = obj.stringOrEmpty("name"),
            argsJson = obj.stringOrEmpty("argsJson"),
        )
    }
}

fun encodeToolResult(result: ToolResult): String {
    val capped = capToolOutput(result.output)
    val cappedNow = capped != result.output
    return buildJsonObject {
        put("status", result.status)
        put("output", capped)
        put(
            "meta",
            buildJsonObject {
                result.meta.forEach { (key, value) -> put(key, value) }
                if (result.truncated || cappedNow) put("truncated", "true")
            },
        )
    }.toString()
}

fun decodeToolResult(raw: String?): AgentToolPayload? {
    val obj = raw?.let(::parseJsonElement) as? JsonObject ?: return null
    val meta = (obj["meta"] as? JsonObject)
        ?.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            if (!primitive.isString) return@mapNotNull null
            key to primitive.content
        }
        ?.toMap()
        .orEmpty()
    return AgentToolPayload(
        status = obj.stringOrEmpty("status"),
        output = obj.stringOrEmpty("output"),
        meta = meta,
    )
}

fun capToolOutput(output: String, maxChars: Int = TOOL_OUTPUT_MAX_CHARS): String {
    require(maxChars > 0) { "maxChars must be positive" }
    return if (output.length <= maxChars) {
        output
    } else {
        output.take(maxChars) + "\n[truncated]"
    }
}

private fun parseJsonElement(raw: String): JsonElement? =
    try {
        Json.parseToJsonElement(raw)
    } catch (e: Exception) {
        null
    }

private fun JsonObject.stringOrEmpty(key: String): String {
    val primitive = this[key] as? JsonPrimitive ?: return ""
    return if (primitive.isString) primitive.content else ""
}
