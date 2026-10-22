package com.aipaca.android.engine.agent

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private val propsJson = Json { ignoreUnknownKeys = true }

fun extractChatTemplateFromProps(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val root = try {
        propsJson.parseToJsonElement(body)
    } catch (e: Exception) {
        return null
    }
    return findChatTemplate(root)
}

private fun findChatTemplate(element: JsonElement): String? = when (element) {
    is JsonObject -> {
        val direct = element["chat_template"]?.let { templateString(it) }
        if (direct != null) direct else element.values.firstNotNullOfOrNull { findChatTemplate(it) }
    }

    is JsonArray -> element.firstNotNullOfOrNull { findChatTemplate(it) }

    else -> null
}

private fun templateString(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }

    is JsonArray -> element.mapNotNull { templateArrayPart(it) }
        .joinToString("\n")
        .takeIf { it.isNotBlank() }

    else -> null
}

private fun templateArrayPart(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull

    is JsonObject -> element["template"]?.let { templateString(it) }

    else -> null
}

suspend fun probeActiveChatTemplate(
    serverUrl: String,
    apiKey: String?,
    client: OkHttpClient = OkHttpClient(),
): String? = withContext(Dispatchers.IO) {
    try {
        val requestBuilder = Request.Builder()
            .url(serverUrl.trimEnd('/') + PROPS_PATH)
            .get()
        if (apiKey != null) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        val probeClient = client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        probeClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            extractChatTemplateFromProps(response.body?.string())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private const val PROPS_PATH = "/props"
private const val PROBE_TIMEOUT_SECONDS = 30L
