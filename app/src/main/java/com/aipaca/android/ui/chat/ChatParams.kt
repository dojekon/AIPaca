package com.aipaca.android.ui.chat

import com.aipaca.android.data.db.entity.ChatMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

fun defaultChatParams(mode: String): ChatViewModel.ChatParamsUi =
    if (mode == ChatMode.AGENT) {
        ChatViewModel.ChatParamsUi(
            temperature = 0.6,
            topP = 0.95,
            topK = 20,
            minP = 0.0,
            repeatPenalty = 1.0,
        )
    } else {
        ChatViewModel.ChatParamsUi()
    }

private val paramsJson = Json

fun encodeChatParams(params: ChatViewModel.ChatParamsUi, mode: String): String? {
    if (params == defaultChatParams(mode)) return null
    return buildJsonObject {
        put(KEY_TEMPERATURE, params.temperature)
        put(KEY_TOP_P, params.topP)
        put(KEY_TOP_K, params.topK)
        put(KEY_MIN_P, params.minP)
        put(KEY_REPEAT_PENALTY, params.repeatPenalty)
        put(KEY_MAX_ITERATIONS, params.maxIterations)
        put(KEY_REASONING_BUDGET, params.reasoningBudget)
    }.toString()
}

fun decodeChatParams(json: String?, mode: String): ChatViewModel.ChatParamsUi {
    val defaults = defaultChatParams(mode)
    if (json.isNullOrBlank()) return defaults
    val root = try {
        paramsJson.parseToJsonElement(json) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return defaults
    fun double(key: String) = (root[key] as? JsonPrimitive)?.doubleOrNull
    fun int(key: String) = (root[key] as? JsonPrimitive)?.intOrNull
    return ChatViewModel.ChatParamsUi(
        temperature = double(KEY_TEMPERATURE) ?: defaults.temperature,
        topP = double(KEY_TOP_P) ?: defaults.topP,
        topK = int(KEY_TOP_K) ?: defaults.topK,
        minP = double(KEY_MIN_P) ?: defaults.minP,
        repeatPenalty = double(KEY_REPEAT_PENALTY) ?: defaults.repeatPenalty,
        maxIterations = int(KEY_MAX_ITERATIONS) ?: defaults.maxIterations,
        reasoningBudget = int(KEY_REASONING_BUDGET) ?: defaults.reasoningBudget,
    )
}

private const val KEY_TEMPERATURE = "temperature"
private const val KEY_TOP_P = "topP"
private const val KEY_TOP_K = "topK"
private const val KEY_MIN_P = "minP"
private const val KEY_REPEAT_PENALTY = "repeatPenalty"
private const val KEY_MAX_ITERATIONS = "maxIterations"
private const val KEY_REASONING_BUDGET = "reasoningBudget"
