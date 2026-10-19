package com.aipaca.android.data.profiles.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ProfileFetcher {

    suspend fun get(url: String, etag: String?): FetchResult

    sealed interface FetchResult {

        data object NotModified : FetchResult

        data class Ok(val body: String, val etag: String?) : FetchResult

        data class Error(val message: String) : FetchResult
    }
}

class OkHttpProfileFetcher(
    baseClient: OkHttpClient = OkHttpClient(),
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : ProfileFetcher {

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun get(url: String, etag: String?): ProfileFetcher.FetchResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == HTTP_NOT_MODIFIED -> ProfileFetcher.FetchResult.NotModified
                        response.isSuccessful -> ProfileFetcher.FetchResult.Ok(
                            body = response.body?.string().orEmpty(),
                            etag = response.header("ETag"),
                        )
                        else -> ProfileFetcher.FetchResult.Error("HTTP ${response.code} for $url")
                    }
                }
            } catch (e: IOException) {
                ProfileFetcher.FetchResult.Error("network error for $url: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    private companion object {

        const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        const val HTTP_NOT_MODIFIED = 304
    }
}
