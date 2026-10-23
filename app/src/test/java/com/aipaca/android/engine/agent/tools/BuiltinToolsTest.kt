package com.aipaca.android.engine.agent.tools

import com.aipaca.android.engine.agent.AgentTool
import com.aipaca.android.engine.agent.AgentToolStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinToolsTest {

    @Test
    fun builtinToolsReturnsToolsInOrder() {
        withSandbox { root ->
            val names = builtinTools(root).map { it.name }
            assertEquals(listOf("list", "find", "grep", "read", "write", "edit", "fetch"), names)
        }
    }

    @Test
    fun everySchemaIsValidJson() {
        withSandbox { root ->
            for (tool in builtinTools(root)) {
                val parsed = Json.parseToJsonElement(tool.parametersJsonSchema)
                assertTrue("${tool.name} schema is not an object", parsed is kotlinx.serialization.json.JsonObject)
                assertTrue(tool.description.isNotBlank())
            }
        }
    }

    @Test
    fun listShowsDirectoriesAndFilesWithSizes() {
        withSandbox { root ->
            File(root, "sub").mkdirs()
            File(root, "a.txt").writeText("hello")

            val result = runBlocking { tool(root, "list").execute("{}") }

            assertEquals(AgentToolStatus.OK, result.status)
            assertEquals(listOf("sub/", "a.txt 5"), result.output.lines())
        }
    }

    @Test
    fun listRejectsFilePath() {
        withSandbox { root ->
            File(root, "a.txt").writeText("hello")

            val result = runBlocking { tool(root, "list").execute("""{"path":"a.txt"}""") }

            assertEquals(AgentToolStatus.ERROR, result.status)
        }
    }

    @Test
    fun findMatchesGlobAndSkipsNonMatching() {
        withSandbox { root ->
            File(root, "sub").mkdirs()
            File(root, "sub/deep").mkdirs()
            File(root, "a.md").writeText("x")
            File(root, "sub/b.txt").writeText("x")
            File(root, "sub/d.md").writeText("x")
            File(root, "sub/deep/c.txt").writeText("x")

            val result = runBlocking { tool(root, "find").execute("""{"pattern":"**/*.txt"}""") }

            assertEquals(AgentToolStatus.OK, result.status)
            assertEquals(listOf("sub/b.txt", "sub/deep/c.txt"), result.output.lines())
        }
    }

    @Test
    fun findRejectsEscapingPath() {
        withSandbox { root ->
            val result = runBlocking { tool(root, "find").execute("""{"pattern":"*","path":"../outside"}""") }

            assertEquals(AgentToolStatus.ERROR, result.status)
        }
    }

    @Test
    fun grepFindsMatchesWithLineNumbers() {
        withSandbox { root ->
            File(root, "sub").mkdirs()
            File(root, "sub/a.txt").writeText("alpha\nneedle here\nneedle again")

            val result = runBlocking { tool(root, "grep").execute("""{"pattern":"needle"}""") }

            assertEquals(AgentToolStatus.OK, result.status)
            assertTrue(result.output.contains("sub/a.txt:2: needle here"))
            assertTrue(result.output.contains("sub/a.txt:3: needle again"))
        }
    }

    @Test
    fun grepRespectsMaxResults() {
        withSandbox { root ->
            File(root, "a.txt").writeText("needle\nneedle\nneedle")

            val result = runBlocking { tool(root, "grep").execute("""{"pattern":"needle","maxResults":1}""") }

            assertEquals(AgentToolStatus.OK, result.status)
            assertEquals("a.txt:1: needle", result.output)
            assertEquals("true", result.meta["truncated"])
        }
    }

    @Test
    fun readSlicesAndNumbersOneBasedLines() {
        withSandbox { root ->
            File(root, "doc.txt").writeText("one\ntwo\nthree\nfour\nfive")

            val result = runBlocking { tool(root, "read").execute("""{"path":"doc.txt","offset":2,"limit":2}""") }

            assertEquals(AgentToolStatus.OK, result.status)
            assertEquals("     2| two\n     3| three", result.output)
        }
    }

    @Test
    fun readRejectsOffsetZero() {
        withSandbox { root ->
            File(root, "doc.txt").writeText("one")

            val result = runBlocking { tool(root, "read").execute("""{"path":"doc.txt","offset":0}""") }

            assertEquals(AgentToolStatus.ERROR, result.status)
        }
    }

    @Test
    fun writeCreatesNestedDirectoriesAndContent() {
        withSandbox { root ->
            val result = runBlocking {
                tool(root, "write").execute("""{"path":"nested/dir/file.txt","content":"hello world"}""")
            }

            assertEquals(AgentToolStatus.OK, result.status)
            assertEquals("Wrote 11 bytes to nested/dir/file.txt", result.output)
            assertEquals("hello world", File(root, "nested/dir/file.txt").readText())
        }
    }

    @Test
    fun editReplacesSingleOccurrence() {
        withSandbox { root ->
            File(root, "a.txt").writeText("hello world")

            val result = runBlocking {
                tool(root, "edit").execute("""{"path":"a.txt","oldString":"world","newString":"there"}""")
            }

            assertEquals(AgentToolStatus.OK, result.status)
            assertEquals("Edited a.txt", result.output)
            assertEquals("hello there", File(root, "a.txt").readText())
        }
    }

    @Test
    fun editErrorsWhenOldStringMissing() {
        withSandbox { root ->
            File(root, "a.txt").writeText("hello")

            val result = runBlocking {
                tool(root, "edit").execute("""{"path":"a.txt","oldString":"zzz","newString":"x"}""")
            }

            assertEquals(AgentToolStatus.ERROR, result.status)
            assertEquals("oldString not found", result.output)
        }
    }

    @Test
    fun editErrorsWhenOldStringIsNotUnique() {
        withSandbox { root ->
            File(root, "a.txt").writeText("a a")

            val result = runBlocking {
                tool(root, "edit").execute("""{"path":"a.txt","oldString":"a","newString":"b"}""")
            }

            assertEquals(AgentToolStatus.ERROR, result.status)
            assertTrue(result.output.contains("2 occurrences"))
        }
    }

    @Test
    fun fetchGetReturnsStatusContentTypeAndBody() {
        withSandbox { root ->
            withHttpServer(::responderFor) { base, _ ->
                val result = runBlocking { FetchTool().execute("""{"url":"$base/hello"}""") }

                assertEquals(AgentToolStatus.OK, result.status)
                assertEquals("200", result.meta["code"])
                assertEquals("HTTP 200 OK\nContent-Type: text/plain\n\nhello agent", result.output)
            }
        }
    }

    @Test
    fun fetchNonSuccessStatusIsErrorButKeepsBody() {
        withSandbox { root ->
            withHttpServer(::responderFor) { base, _ ->
                val result = runBlocking { FetchTool().execute("""{"url":"$base/fail"}""") }

                assertEquals(AgentToolStatus.ERROR, result.status)
                assertEquals("500", result.meta["code"])
                assertTrue(result.output.contains("boom"))
            }
        }
    }

    @Test
    fun fetchRejectsNonHttpScheme() {
        withSandbox { root ->
            val result = runBlocking { FetchTool().execute("""{"url":"ftp://example.com/file"}""") }

            assertEquals(AgentToolStatus.ERROR, result.status)
        }
    }

    @Test
    fun fetchPostSendsBody() {
        withSandbox { root ->
            withHttpServer(::responderFor) { base, _ ->
                val result = runBlocking {
                    FetchTool().execute("""{"url":"$base/echo","method":"POST","body":"payload"}""")
                }

                assertEquals(AgentToolStatus.OK, result.status)
                assertTrue(result.output.endsWith("payload"))
            }
        }
    }

    @Test
    fun fetchPagesLargeBodiesWithOffsetHint() {
        withSandbox { root ->
            withHttpServer(::responderFor) { base, _ ->
                val result = runBlocking { FetchTool(pageChars = { 1000 }).execute("""{"url":"$base/big"}""") }

                assertEquals(AgentToolStatus.OK, result.status)
                assertTrue(result.truncated)
                assertEquals("1000", result.meta["nextOffset"])
                assertTrue(result.output.contains("x".repeat(1000) + "\n\n[showing characters 0-1000 of 70000"))
                assertTrue(result.output.endsWith("call fetch with offset=1000 to read more]"))
            }
        }
    }

    @Test
    fun fetchOffsetReturnsLaterPageAndLastPageHasNoHint() {
        withSandbox { root ->
            withHttpServer(::responderFor) { base, _ ->
                val result = runBlocking {
                    FetchTool(pageChars = { 1000 }).execute("""{"url":"$base/big","offset":69500}""")
                }

                assertEquals(null, result.meta["nextOffset"])
                assertTrue(result.output.endsWith("\n\n" + "x".repeat(500)))
            }
        }
    }

    @Test
    fun fetchSendsDefaultUserAgentUnlessProvided() {
        withSandbox { root ->
            withHttpServer(::responderFor) { base, requests ->
                runBlocking { FetchTool().execute("""{"url":"$base/hello"}""") }
                runBlocking {
                    FetchTool().execute("""{"url":"$base/hello","headers":{"user-agent":"Custom/1"}}""")
                }

                val sent = requests()
                assertTrue(sent[0].contains("User-Agent: $FETCH_USER_AGENT"))
                assertTrue(sent[1].contains("Custom/1"))
                assertTrue(!sent[1].contains(FETCH_USER_AGENT))
            }
        }
    }

    @Test
    fun fetchConvertsHtmlToText() {
        withSandbox { root ->
            withHttpServer(::responderFor) { base, _ ->
                val result = runBlocking { FetchTool().execute("""{"url":"$base/page"}""") }

                assertEquals("true", result.meta["html"])
                assertTrue(result.output.contains("Title: Example & Co"))
                assertTrue(result.output.contains("# Heading"))
                assertTrue(result.output.contains("- first"))
                assertTrue(!result.output.contains("<p>") && !result.output.contains("tracking()"))
            }
        }
    }

    private fun tool(root: File, name: String): AgentTool =
        builtinTools(root).first { it.name == name }

    private inline fun withSandbox(block: (File) -> Unit) {
        val root = Files.createTempDirectory("agent-tools-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Response(
        val statusLine: String,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    ) {
        constructor(statusLine: String, headers: Map<String, String> = emptyMap(), body: String) :
            this(statusLine, headers, body.toByteArray())
    }

    private fun responderFor(requestText: String): Response {
        val path = requestText.lineSequence().first().split(' ').getOrNull(1).orEmpty()
        return when {
            path.startsWith("/hello") ->
                Response("HTTP/1.1 200 OK", mapOf("Content-Type" to "text/plain"), "hello agent")
            path.startsWith("/fail") ->
                Response("HTTP/1.1 500 Internal Server Error", mapOf("Content-Type" to "text/plain"), "boom")
            path.startsWith("/big") ->
                Response("HTTP/1.1 200 OK", mapOf("Content-Type" to "text/plain"), "x".repeat(70_000))
            path.startsWith("/page") ->
                Response(
                    "HTTP/1.1 200 OK",
                    mapOf("Content-Type" to "text/html; charset=utf-8"),
                    "<html><head><title>Example &amp; Co</title><script>tracking()</script></head>" +
                        "<body><h1>Heading</h1><p>Body text</p><ul><li>first</li><li>second</li></ul></body></html>",
                )
            path.startsWith("/echo") ->
                Response(
                    "HTTP/1.1 200 OK",
                    mapOf("Content-Type" to "text/plain"),
                    requestText.substringAfter("\r\n\r\n", ""),
                )
            else ->
                Response("HTTP/1.1 404 Not Found", mapOf("Content-Type" to "text/plain"), "not found")
        }
    }

    private inline fun withHttpServer(
        crossinline responder: (requestText: String) -> Response,
        block: (baseUrl: String, requests: () -> List<String>) -> Unit,
    ) {
        val server = ServerSocket()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val thread = thread(isDaemon = true, name = "test-http-server") {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    socket.use {
                        val requestText = readRequest(it)
                        requests += requestText
                        writeResponse(it, responder(requestText))
                    }
                }
            } catch (_: IOException) {
            }
        }
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        try {
            block(baseUrl, { synchronized(requests) { requests.toList() } })
        } finally {
            server.close()
            thread.join(1000)
        }
    }

    private fun readRequest(socket: Socket): String {
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        val terminator = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (matched < terminator.size) {
            val value = input.read()
            if (value == -1) break
            headerBytes.write(value)
            matched = when (value.toByte()) {
                terminator[matched] -> matched + 1
                terminator[0] -> 1
                else -> 0
            }
        }
        val headerText = headerBytes.toString("ISO-8859-1")
        val contentLength = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(bytes, read, contentLength - read)
                if (count < 0) break
                read += count
            }
            String(bytes, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return headerText + body
    }

    private fun writeResponse(socket: Socket, response: Response) {
        val head = buildString {
            append(response.statusLine).append("\r\n")
            response.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            if (response.headers.keys.none { it.equals("Content-Length", ignoreCase = true) }) {
                append("Content-Length: ").append(response.body.size).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(response.body)
            flush()
        }
    }
}
