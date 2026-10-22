package com.aipaca.android.engine.agent

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
import org.junit.Test

class ModelToolProbeTest {

    @Test
    fun rootLevelChatTemplateIsExtracted() {
        val json = """{"chat_template": "{% for message in messages %}{{ message.content }}{% endfor %}"}"""

        assertEquals(
            "{% for message in messages %}{{ message.content }}{% endfor %}",
            extractChatTemplateFromProps(json),
        )
    }

    @Test
    fun nestedDefaultGenerationSettingsTemplateIsExtracted() {
        val json = """{"default_generation_settings": {"chat_template": "nested-template"}}"""

        assertEquals("nested-template", extractChatTemplateFromProps(json))
    }

    @Test
    fun rootLevelTemplateIsPreferredOverNested() {
        val json =
            """{"chat_template": "root", "default_generation_settings": {"chat_template": "nested"}}"""

        assertEquals("root", extractChatTemplateFromProps(json))
    }

    @Test
    fun escapedUnicodeAndNewlinesAreDecoded() {
        val json = """{"chat_template": "line1\n\u041f\u0440\u0438\u0432\u0435\u0442 \"quoted\""}"""

        assertEquals("line1\nПривет \"quoted\"", extractChatTemplateFromProps(json))
    }

    @Test
    fun arrayOfStringsIsJoinedWithNewlines() {
        val json = """{"chat_template": ["part-a", "part-b"]}"""

        assertEquals("part-a\npart-b", extractChatTemplateFromProps(json))
    }

    @Test
    fun arrayOfObjectsUsesTemplateFields() {
        val json =
            """{"chat_template": [{"name": "default", "template": "a"}, {"name": "tool", "template": "b"}]}"""

        assertEquals("a\nb", extractChatTemplateFromProps(json))
    }

    @Test
    fun arrayOfObjectsSkipsEntriesWithoutTemplate() {
        val json = """{"chat_template": [{"name": "x"}, {"template": "real"}]}"""

        assertEquals("real", extractChatTemplateFromProps(json))
    }

    @Test
    fun nullBlankMissingAndInvalidInputsReturnNull() {
        assertNull(extractChatTemplateFromProps(null))
        assertNull(extractChatTemplateFromProps(""))
        assertNull(extractChatTemplateFromProps("   "))
        assertNull(extractChatTemplateFromProps("not json"))
        assertNull(extractChatTemplateFromProps("""{"other": "value"}"""))
        assertNull(extractChatTemplateFromProps("""{"chat_template": null}"""))
        assertNull(extractChatTemplateFromProps("""{"chat_template": "   "}"""))
        assertNull(extractChatTemplateFromProps("""{"chat_template": []}"""))
    }

    @Test
    fun templateWithoutToolMarkersIsStillReturned() {
        val template = "{% for message in messages %}{{ message.content }}{% endfor %}"

        assertEquals(template, extractChatTemplateFromProps("""{"chat_template": "$template"}"""))
    }

    @Test
    fun probeReturnsTemplateFromSuccessfulResponse() = runBlocking {
        val template = "{% if tools %}tool_call{% endif %}"
        val client = client { request -> response(request, 200, """{"chat_template": "$template"}""") }

        assertEquals(template, probeActiveChatTemplate("http://localhost:8080", null, client))
    }

    @Test
    fun probeReadsNestedTemplateFromProps() = runBlocking {
        val client = client { request ->
            response(
                request,
                200,
                """{"default_generation_settings": {"chat_template": "nested"}}""",
            )
        }

        assertEquals("nested", probeActiveChatTemplate("http://localhost:8080", null, client))
    }

    @Test
    fun probeRequestsPropsPath() = runBlocking {
        var path: String? = null
        val client = client { request ->
            path = request.url.encodedPath
            response(request, 200, """{"chat_template": "t"}""")
        }

        probeActiveChatTemplate("http://localhost:8080", null, client)

        assertEquals("/props", path)
    }

    @Test
    fun probeReturnsNullOnNotFound() = runBlocking {
        val client = client { request -> response(request, 404, """{"error": "not found"}""") }

        assertNull(probeActiveChatTemplate("http://localhost:8080", null, client))
    }

    @Test
    fun probeReturnsNullOnNullBody() = runBlocking {
        val client = client { request -> response(request, 200, null) }

        assertNull(probeActiveChatTemplate("http://localhost:8080", null, client))
    }

    @Test
    fun probeReturnsNullOnInvalidJson() = runBlocking {
        val client = client { request -> response(request, 200, "not json") }

        assertNull(probeActiveChatTemplate("http://localhost:8080", null, client))
    }

    @Test
    fun probeReturnsNullOnNetworkError() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("boom") }
            .build()

        assertNull(probeActiveChatTemplate("http://localhost:8080", null, client))
    }

    @Test
    fun probeSendsBearerAuthorizationWhenApiKeyPresent() = runBlocking {
        var authorization: String? = null
        val client = client { request ->
            authorization = request.header("Authorization")
            response(request, 200, """{"chat_template": "t"}""")
        }

        probeActiveChatTemplate("http://localhost:8080", "secret", client)

        assertEquals("Bearer secret", authorization)
    }

    @Test
    fun probeOmitsAuthorizationWithoutApiKey() = runBlocking {
        var authorization: String? = "unset"
        val client = client { request ->
            authorization = request.header("Authorization")
            response(request, 200, """{"chat_template": "t"}""")
        }

        probeActiveChatTemplate("http://localhost:8080", null, client)

        assertNull(authorization)
    }

    private fun client(respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> respond(chain.request()) })
            .build()

    private fun response(request: Request, code: Int, body: String?): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body(body?.toResponseBody("application/json".toMediaType()))
            .build()
}
