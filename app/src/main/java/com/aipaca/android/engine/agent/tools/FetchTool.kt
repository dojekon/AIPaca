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

const val FETCH_DEFAULT_PAGE_CHARS = 16 * 1024
private const val FETCH_MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024

class FetchTool(
    private val client: OkHttpClient = defaultFetchClient(),
    private val pageChars: () -> Int = { FETCH_DEFAULT_PAGE_CHARS },
) : AgentTool {
    override val name: String = "fetch"

    override val description: String =
        "Fetch an HTTP or HTTPS URL. HTML is converted to plain text; long results are paged, " +
            "pass offset to read the next page."

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
            val offset = params.optionalInt("offset").getOrElse { return errorResult(it.message) } ?: 0
            if (offset < 0) return errorResult("offset must be >= 0")

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
                    val source = response.body?.source()
                    source?.request(FETCH_MAX_DOWNLOAD_BYTES)
                    val bytes = source?.buffer?.let { buffer ->
                        buffer.readByteArray(minOf(buffer.size, FETCH_MAX_DOWNLOAD_BYTES))
                    } ?: ByteArray(0)
                    val rawBody = String(bytes, response.body?.contentType()?.charset() ?: Charsets.UTF_8)
                    val html = looksLikeHtml(contentType, rawBody)
                    val document = if (html) htmlToText(rawBody) else HtmlDocumentText(null, rawBody)
                    val page = pageOf(document.text, offset, pageChars())
                    val output = buildString {
                        append("HTTP $code $message\nContent-Type: $contentType\n")
                        document.title?.let { append("Title: $it\n") }
                        append('\n')
                        append(page.text)
                        if (page.hasMore) {
                            append(
                                "\n\n[showing characters ${page.offset}-${page.end} of ${page.total}; " +
                                    "call fetch with offset=${page.end} to read more]",
                            )
                        }
                    }
                    val status = if (code in 200..299) AgentToolStatus.OK else AgentToolStatus.ERROR
                    ToolResult(
                        status = status,
                        output = output,
                        truncated = page.hasMore,
                        meta = buildMap {
                            put("code", code.toString())
                            put("contentType", contentType)
                            put("chars", page.total.toString())
                            if (html) put("html", "true")
                            if (page.hasMore) put("nextOffset", page.end.toString())
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
        },
        "offset": {
          "type": "integer",
          "description": "Character offset of the page to return; defaults to 0."
        }
      },
      "required": ["url"]
    }
""".trimIndent()
