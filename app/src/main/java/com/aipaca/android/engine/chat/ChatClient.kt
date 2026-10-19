package com.aipaca.android.engine.chat

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val json = Json

class ChatClient(private val client: OkHttpClient = defaultClient()) {

    suspend fun stream(
        serverUrl: String,
        apiKey: String?,
        request: ChatRequest,
        onDelta: (String, Boolean) -> Unit,
    ): Result<ChatStreamResult> = withContext(Dispatchers.IO) {
        val call = client.newCall(buildRequest(serverUrl, apiKey, request, stream = true))
        val cancelWatcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty().take(MAX_ERROR_BODY_CHARS)
                    return@withContext Result.failure(IOException("HTTP ${response.code}: $body"))
                }

                val reader = response.body?.charStream()?.buffered()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                val content = StringBuilder()
                var timings: ChatTimings? = null

                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || !trimmed.startsWith(DATA_PREFIX)) continue
                    when (val event = parseSsePayload(trimmed.removePrefix(DATA_PREFIX))) {
                        is ChatEvent.Delta -> {
                            if (!event.isReasoning) {
                                content.append(event.text)
                            }
                            onDelta(event.text, event.isReasoning)
                        }

                        is ChatEvent.Done -> if (event.timings != null) timings = event.timings
                        ChatEvent.Ignored -> Unit
                    }
                }

                Result.success(ChatStreamResult(content.toString(), timings))
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            Result.failure(e)
        } finally {
            cancelWatcher.cancel()
        }
    }

    suspend fun complete(
        serverUrl: String,
        apiKey: String?,
        request: ChatRequest,
        timeoutSeconds: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        val call = client.newCall(buildRequest(serverUrl, apiKey, request, stream = false))
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        val cancelWatcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty().take(MAX_ERROR_BODY_CHARS)
                    return@withContext Result.failure(IOException("HTTP ${response.code}: $body"))
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))
                val content = parseCompletionContent(body)
                    ?: return@withContext Result.failure(IOException("Unexpected completion response"))
                Result.success(content)
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            Result.failure(e)
        } finally {
            cancelWatcher.cancel()
        }
    }

    private fun buildRequest(
        serverUrl: String,
        apiKey: String?,
        chat: ChatRequest,
        stream: Boolean,
    ): Request {
        val body = buildRequestBody(chat, stream).toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url("$serverUrl/v1/chat/completions")
            .post(body)
            .apply { if (apiKey != null) header("Authorization", "Bearer $apiKey") }
            .build()
    }

    private fun buildRequestBody(chat: ChatRequest, stream: Boolean): String {
        val payload = buildJsonObject {
            put("model", "local")
            putJsonArray("messages") {
                buildApiMessages(chat).forEach { message ->
                    addJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    }
                }
            }
            put("stream", stream)
            put("temperature", chat.temperature)
            put("top_p", chat.topP)
            put("top_k", chat.topK)
            put("min_p", chat.minP)
            put("repeat_penalty", chat.repeatPenalty)
            chat.maxTokens?.let { put("max_tokens", it) }
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun parseCompletionContent(body: String): String? {
        val root = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            return null
        }
        val choices = (root as? JsonObject)?.get("choices") as? JsonArray ?: return null
        val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: return null
        val content = message["content"] as? JsonPrimitive ?: return null
        if (!content.isString) return null
        return content.content
    }

    companion object {

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DATA_PREFIX = "data:"
        private const val MAX_ERROR_BODY_CHARS = 500
    }
}
