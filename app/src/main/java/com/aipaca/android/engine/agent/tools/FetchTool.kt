package com.aipaca.android.engine.agent.tools

import com.aipaca.android.engine.agent.AgentTool
import com.aipaca.android.engine.agent.AgentToolStatus
import com.aipaca.android.engine.agent.ToolResult
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val FETCH_MAX_CHARS = 64 * 1024

class FetchTool(private val client: OkHttpClient = defaultFetchClient()) : AgentTool {
    override val name: String = "fetch"

    override val description: String = "Fetch an HTTP or HTTPS URL and return the response text."

    override val parametersJsonSchema: String = FETCH_SCHEMA

    override suspend fun execute(paramsJson: String): ToolResult {
        return try {
            val params = readParams(paramsJson).getOrElse { return errorResult(it.message) }
            val urlText = params.requiredString("url").getOrElse { return errorResult(it.message) }
            val method = (params.optionalString("method").getOrElse { return errorResult(it.message) }
                ?: "GET").uppercase(Locale.ROOT)
            if (method != "GET" && method != "POST") {
                return errorResult("Unsupported method: $method")
            }

            val url = urlText.toHttpUrlOrNull()
                ?: return errorResult("Invalid URL: $urlText")
            if (url.scheme != "http" && url.scheme != "https") {
                return errorResult("Only http and https URLs are supported")
            }

            val headers = params.readHeaders().getOrElse { return errorResult(it.message) }
            val body = params.optionalString("body").getOrElse { return errorResult(it.message) }

            val builder = Request.Builder().url(url)
            headers.forEach { (key, value) -> builder.addHeader(key, value) }
            val request = if (method == "POST") {
                builder.post((body ?: "").toRequestBody(null)).build()
            } else {
                builder.get().build()
            }

            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val message = response.message
                    val contentType = response.header("Content-Type") ?: "unknown"
                    val rawBody = response.body?.string().orEmpty()
                    val truncated = rawBody.length > FETCH_MAX_CHARS
                    val bodyText = if (truncated) {
                        rawBody.take(FETCH_MAX_CHARS) + "\n[truncated]"
                    } else {
                        rawBody
                    }
                    val output = "HTTP $code $message\nContent-Type: $contentType\n\n$bodyText"
                    val status = if (code in 200..299) AgentToolStatus.OK else AgentToolStatus.ERROR
                    ToolResult(
                        status = status,
                        output = output,
                        truncated = truncated,
                        meta = buildMap {
                            put("code", code.toString())
                            put("contentType", contentType)
                            if (truncated) put("truncated", "true")
                        },
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(e.message ?: e.javaClass.simpleName)
        }
    }
}

private fun JsonObject.readHeaders(): Result<Map<String, String>> {
    val element = this["headers"] ?: return Result.success(emptyMap())
    if (element !is JsonObject) {
        return Result.failure(IllegalArgumentException("Parameter headers must be an object"))
    }
    val headers = mutableMapOf<String, String>()
    for ((key, value) in element) {
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            return Result.failure(IllegalArgumentException("Header $key must be a string"))
        }
        headers[key] = primitive.content
    }
    return Result.success(headers)
}

private val FETCH_SCHEMA = """
    {
      "type": "object",
      "properties": {
        "url": {
          "type": "string",
          "description": "HTTP or HTTPS URL to fetch."
        },
        "method": {
          "type": "string",
          "description": "HTTP method, GET or POST; defaults to GET."
        },
        "headers": {
          "type": "object",
          "additionalProperties": { "type": "string" }
        },
        "body": {
          "type": "string",
          "description": "Request body for POST; ignored for GET."
        }
      },
      "required": ["url"]
    }
""".trimIndent()
