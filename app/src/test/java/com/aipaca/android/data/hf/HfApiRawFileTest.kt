package com.aipaca.android.data.hf

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfApiRawFileTest {

    private class Recorder {
        var lastRequest: Request? = null
    }

    private fun api(
        recorder: Recorder,
        token: String = "",
        respond: (Request) -> Response,
    ): HfApi {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            recorder.lastRequest = request
            respond(request)
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return HfApi(client, { token })
    }

    private fun response(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body(body.toResponseBody("text/plain".toMediaType()))
            .build()

    private fun notFound(request: Request): Response = response(request, 404, "")

    @Test
    fun fetchRawFileReturnsBodyAndBuildsRequestWithToken() = runBlocking {
        val recorder = Recorder()
        val api = api(recorder, token = "hf_secret") { request -> response(request, 200, "hello") }

        val result = api.fetchRawFile("org/repo", "tokenizer_config.json")

        assertEquals("hello", result.getOrNull())
        val request = recorder.lastRequest!!
        assertEquals(
            "https://huggingface.co/org/repo/resolve/main/tokenizer_config.json",
            request.url.toString(),
        )
        assertEquals("Bearer hf_secret", request.header("Authorization"))
    }

    @Test
    fun fetchRawFileOmitsAuthorizationWithoutToken() = runBlocking {
        val recorder = Recorder()
        val api = api(recorder) { request -> response(request, 200, "hello") }

        val result = api.fetchRawFile("org/repo", "tokenizer_config.json")

        assertEquals("hello", result.getOrNull())
        assertNull(recorder.lastRequest?.header("Authorization"))
    }

    @Test
    fun fetchRawFileReturnsNullOn404() = runBlocking {
        val api = api(Recorder()) { request -> notFound(request) }

        val result = api.fetchRawFile("org/repo", "tokenizer_config.json")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun fetchRawFileReturnsAuthFailureOn401() = runBlocking {
        val api = api(Recorder()) { request -> response(request, 401, "unauthorized") }

        val result = api.fetchRawFile("org/repo", "tokenizer_config.json")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as IOException
        assertTrue(error.message!!.contains("token"))
    }

    @Test
    fun fetchRawFileTruncatesBodyToMaxBytes() = runBlocking {
        val large = "a".repeat(1000)
        val api = api(Recorder()) { request -> response(request, 200, large) }

        val result = api.fetchRawFile("org/repo", "tokenizer_config.json", maxBytes = 10L)

        assertEquals(10, result.getOrNull()!!.length)
    }

    @Test
    fun fetchRawFileBlankBodyReturnsNull() = runBlocking {
        val api = api(Recorder()) { request -> response(request, 200, "   \n  ") }

        val result = api.fetchRawFile("org/repo", "tokenizer_config.json")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun fetchChatTemplateUsesTokenizerConfigTemplate() = runBlocking {
        val recorder = Recorder()
        val template = "{% if tools %}<|tool_calls|>{% endif %}"
        val api = api(recorder) { request ->
            response(request, 200, """{"chat_template": "$template"}""")
        }

        val result = api.fetchChatTemplate("org/repo")

        assertEquals(template, result.getOrNull())
        assertTrue(recorder.lastRequest!!.url.toString().endsWith("/tokenizer_config.json"))
    }

    @Test
    fun fetchChatTemplateFallsBackToJinjaFile() = runBlocking {
        val recorder = Recorder()
        val jinja = "{% for message in messages %}{{ message.content }}{% endfor %}"
        val api = api(recorder) { request ->
            if (request.url.toString().endsWith("/chat_template.jinja")) {
                response(request, 200, jinja)
            } else {
                response(request, 200, """{"model_max_length": 4096}""")
            }
        }

        val result = api.fetchChatTemplate("org/repo")

        assertEquals(jinja, result.getOrNull())
        assertTrue(recorder.lastRequest!!.url.toString().endsWith("/chat_template.jinja"))
    }

    @Test
    fun fetchChatTemplateReturnsNullWhenBothMissing() = runBlocking {
        val api = api(Recorder()) { request -> notFound(request) }

        val result = api.fetchChatTemplate("org/repo")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }
}
