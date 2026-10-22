package com.aipaca.android.engine.agent.tools

import com.aipaca.android.engine.agent.AgentTool
import com.aipaca.android.engine.agent.AgentToolStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEdgeCasesTest {

    @Test
    fun editReplacesMultilineUnicodeBlock() = withSandbox { root ->
        File(root, "doc.txt").writeText("intro\nПривет мир\nвторой\nend")

        val result = runBlocking {
            tool(root, "edit").execute(
                """{"path":"doc.txt","oldString":"Привет мир\nвторой","newString":"Hello 🌍\nline two"}""",
            )
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals("intro\nHello 🌍\nline two\nend", File(root, "doc.txt").readText())
    }

    @Test
    fun editHandlesEscapedNewlinesAndQuotes() = withSandbox { root ->
        File(root, "doc.txt").writeText("say \"hi\"\nC:\\temp")

        val result = runBlocking {
            tool(root, "edit").execute(
                """{"path":"doc.txt","oldString":"say \"hi\"\nC:\\temp","newString":"say \"bye\"\nD:\\data"}""",
            )
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals("say \"bye\"\nD:\\data", File(root, "doc.txt").readText())
    }

    @Test
    fun editErrorsWhenOldStringIsAbsent() = withSandbox { root ->
        File(root, "doc.txt").writeText("alpha\nbeta\ngamma")

        val result = runBlocking {
            tool(root, "edit").execute(
                """{"path":"doc.txt","oldString":"alpha\nmissing","newString":"x"}""",
            )
        }

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertEquals("oldString not found", result.output)
        assertEquals("alpha\nbeta\ngamma", File(root, "doc.txt").readText())
    }

    @Test
    fun editErrorsWhenOldStringAppearsThreeTimes() = withSandbox { root ->
        File(root, "doc.txt").writeText("foo foo foo")

        val result = runBlocking {
            tool(root, "edit").execute("""{"path":"doc.txt","oldString":"foo","newString":"bar"}""")
        }

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertTrue(result.output.contains("3 occurrences"))
        assertEquals("foo foo foo", File(root, "doc.txt").readText())
    }

    @Test
    fun readOffsetBeyondEofReturnsEmptyOutputAndOk() = withSandbox { root ->
        File(root, "doc.txt").writeText("one\ntwo\nthree")

        val result = runBlocking {
            tool(root, "read").execute("""{"path":"doc.txt","offset":10}""")
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals("", result.output)
    }

    @Test
    fun readLimitLargerThanFileReturnsAllLines() = withSandbox { root ->
        File(root, "doc.txt").writeText("one\ntwo\nthree")

        val result = runBlocking {
            tool(root, "read").execute("""{"path":"doc.txt","limit":1000}""")
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals(
            listOf("     1| one", "     2| two", "     3| three"),
            result.output.lines(),
        )
    }

    @Test
    fun readKeepsOriginalLineNumberWhenOffsetAboveOne() = withSandbox { root ->
        File(root, "doc.txt").writeText("one\ntwo\nthree\nfour\nfive")

        val result = runBlocking {
            tool(root, "read").execute("""{"path":"doc.txt","offset":4}""")
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals(listOf("     4| four", "     5| five"), result.output.lines())
    }

    @Test
    fun findMatchesDirectoriesAsWellAsFiles() = withSandbox { root ->
        File(root, "sub").mkdirs()
        File(root, "sub/deep").mkdirs()
        File(root, "sub/a.txt").writeText("x")

        val result = runBlocking {
            tool(root, "find").execute("""{"pattern":"sub/*"}""")
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals(listOf("sub/a.txt", "sub/deep"), result.output.lines())
    }

    @Test
    fun findPatternWithSlashMatchesNestedPaths() = withSandbox { root ->
        File(root, "nested").mkdirs()
        File(root, "nested/note.txt").writeText("x")
        File(root, "nested/other.md").writeText("x")

        val result = runBlocking {
            tool(root, "find").execute("""{"pattern":"nested/*.txt"}""")
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals(listOf("nested/note.txt"), result.output.lines())
    }

    @Test
    fun findPatternWithoutSlashMatchesNestedFiles() = withSandbox { root ->
        File(root, "top.md").writeText("x")
        File(root, "nested").mkdirs()
        File(root, "nested/a.txt").writeText("x")
        File(root, "nested/deep").mkdirs()
        File(root, "nested/deep/b.txt").writeText("x")

        val result = runBlocking {
            tool(root, "find").execute("""{"pattern":"*.txt"}""")
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals(listOf("nested/a.txt", "nested/deep/b.txt"), result.output.lines())
    }

    @Test
    fun grepInvalidRegexFallsBackToLiteralSearch() = withSandbox { root ->
        File(root, "doc.txt").writeText("literal a(b here\nnothing else")

        val result = runBlocking {
            tool(root, "grep").execute("""{"pattern":"a(b"}""")
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals("doc.txt:1: literal a(b here", result.output)
    }

    @Test
    fun grepMaxResultsCapsOutputAndMarksTruncated() = withSandbox { root ->
        File(root, "doc.txt").writeText("hit one\nhit two\nhit three")

        val result = runBlocking {
            tool(root, "grep").execute("""{"pattern":"hit","maxResults":2}""")
        }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals(listOf("doc.txt:1: hit one", "doc.txt:2: hit two"), result.output.lines())
        assertTrue(result.truncated)
        assertEquals("true", result.meta["truncated"])
    }

    @Test
    fun writeUnicodeAndNewlinesRoundTripsThroughRead() = withSandbox { root ->
        val writeResult = runBlocking {
            tool(root, "write").execute(
                """{"path":"unicode.txt","content":"Привет\n世界 🌍\nline \"quoted\""}""",
            )
        }

        assertEquals(AgentToolStatus.OK, writeResult.status)
        assertEquals("Привет\n世界 🌍\nline \"quoted\"", File(root, "unicode.txt").readText())

        val readResult = runBlocking {
            tool(root, "read").execute("""{"path":"unicode.txt"}""")
        }

        assertEquals(AgentToolStatus.OK, readResult.status)
        assertEquals(
            listOf("     1| Привет", "     2| 世界 🌍", "     3| line \"quoted\""),
            readResult.output.lines(),
        )
    }

    @Test
    fun fetchRejectsMissingUrl() = withSandbox { root ->
        val result = runBlocking { tool(root, "fetch").execute("{}") }

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertTrue(result.output.contains("Missing required parameter: url"))
    }

    @Test
    fun fetchRejectsNonHttpScheme() = withSandbox { root ->
        val result = runBlocking {
            tool(root, "fetch").execute("""{"url":"ftp://example.com/file"}""")
        }

        assertEquals(AgentToolStatus.ERROR, result.status)
    }

    @Test
    fun fetchRejectsUnsupportedMethod() = withSandbox { root ->
        val result = runBlocking {
            tool(root, "fetch").execute("""{"url":"http://example.com","method":"PUT"}""")
        }

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertTrue(result.output.contains("Unsupported method: PUT"))
    }

    @Test
    fun fetchRejectsNonStringHeaderValue() = withSandbox { root ->
        val result = runBlocking {
            tool(root, "fetch").execute("""{"url":"http://example.com","headers":{"X-Test":42}}""")
        }

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertTrue(result.output.contains("Header X-Test must be a string"))
    }

    @Test
    fun listEmptyDirectoryReturnsOkWithEmptyOutput() = withSandbox { root ->
        val result = runBlocking { tool(root, "list").execute("{}") }

        assertEquals(AgentToolStatus.OK, result.status)
        assertEquals("", result.output)
    }

    @Test
    fun listRejectsPathTraversal() = withSandbox { root ->
        val result = runBlocking { tool(root, "list").execute("""{"path":"../x"}""") }

        assertEquals(AgentToolStatus.ERROR, result.status)
    }

    private fun tool(root: File, name: String): AgentTool =
        builtinTools(root).first { it.name == name }

    private inline fun withSandbox(block: (File) -> Unit) {
        val root = Files.createTempDirectory("agent-tool-edges-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
