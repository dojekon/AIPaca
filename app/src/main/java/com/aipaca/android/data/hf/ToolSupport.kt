package com.aipaca.android.data.hf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val toolTagMarkers = listOf(
    "tool-use",
    "tool_use",
    "function-calling",
    "function_calling",
)

private val toolTemplateMarkers = listOf(
    "\"tools\"",
    "tool_call",
    "tool_calls",
    "<|tool\u2581calls|>",
    "tool_call_start",
)

private val toolJson = Json { ignoreUnknownKeys = true }

fun detectTools(
    tags: List<String>,
    fileNames: List<String>,
    chatTemplateText: String?,
): Boolean? {
    if (tags.any { tag -> toolTagMarkers.any { tag.trim().lowercase().contains(it) } }) return true
    if (chatTemplateText != null) {
        return containsToolMarkers(chatTemplateText)
    }
    return null
}

fun toolsSupportedValue(detected: Boolean?): Int? = detected?.let { if (it) 1 else 0 }

fun containsToolMarkers(templateText: String): Boolean {
    val normalized = templateText.lowercase()
    return toolTemplateMarkers.any { normalized.contains(it) }
}

fun hasChatTemplateFile(fileNames: List<String>): Boolean =
    fileNames.any { name ->
        name.endsWith("tokenizer_config.json") || name.endsWith("chat_template.jinja")
    }

fun extractChatTemplate(tokenizerConfigJson: String?): String? {
    if (tokenizerConfigJson.isNullOrBlank()) return null
    return try {
        val root = toolJson.parseToJsonElement(tokenizerConfigJson)
        val chatTemplate = (root as? JsonObject)?.get("chat_template") ?: return null
        val extracted = when (chatTemplate) {
            is JsonPrimitive -> if (chatTemplate.isString) chatTemplate.content else null
            is JsonArray -> joinTemplateArray(chatTemplate)
            else -> null
        }
        extracted?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}

private fun joinTemplateArray(array: JsonArray): String? {
    val parts = array.mapNotNull { element -> templatePart(element) }
    if (parts.isEmpty()) return null
    return parts.joinToString("\n").takeIf { it.isNotBlank() }
}

private fun templatePart(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> if (element.isString) element.content else null
    is JsonObject -> (element["template"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    else -> null
}
