package com.aipaca.android.data.hf

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private val json = Json { ignoreUnknownKeys = true }

private const val BASE_URL = "https://huggingface.co"

class HfApi(
    private val client: OkHttpClient,
    private val tokenProvider: () -> String,
) {

    suspend fun searchModels(query: String, limit: Int = 30): Result<List<HfModelSummary>> =
        withContext(Dispatchers.IO) {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addPathSegment("api")
                .addPathSegment("models")
                .addQueryParameter("search", query)
                .addQueryParameter("filter", "gguf")
                .addQueryParameter("sort", "downloads")
                .addQueryParameter("limit", limit.toString())
                .build()
            get(url) { body -> json.decodeFromString<List<HfModelSummary>>(body) }
        }

    suspend fun modelDetails(repoId: String): Result<HfModelDetails> =
        withContext(Dispatchers.IO) {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addPathSegment("api")
                .addPathSegment("models")
                .apply { repoId.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
                .addQueryParameter("blobs", "true")
                .build()
            get(url, authHint = true) { body -> json.decodeFromString<HfModelDetails>(body) }
        }

    private fun <T> get(url: HttpUrl, authHint: Boolean = false, parse: (String) -> T): Result<T> =
        try {
            val request = Request.Builder()
                .url(url)
                .apply {
                    val token = tokenProvider()
                    if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException(errorMessage(response.code, authHint)))
                } else {
                    Result.success(parse(response.body?.string().orEmpty()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IOException(e.message ?: e.javaClass.simpleName, e))
        }

    private fun errorMessage(code: Int, authHint: Boolean): String = when {
        authHint && code == 401 -> "HTTP 401: Hugging Face token required, set an access token"
        authHint && code == 403 -> "HTTP 403: access denied, the model may be gated"
        else -> "HTTP $code"
    }
}
