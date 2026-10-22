package com.aipaca.android.engine.agent.tools

import com.aipaca.android.engine.agent.AgentTool
import com.aipaca.android.engine.agent.AgentToolStatus
import com.aipaca.android.engine.agent.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AgentToolRegistryTest {

    @Test
    fun manifestIncludesAllToolsSortedByName() {
        val zeta = FakeTool("zeta")
        val alpha = FakeTool("alpha")
        val mike = FakeTool("mike")

        val registry = AgentToolRegistry(listOf(zeta, mike, alpha))

        assertEquals(listOf("alpha", "mike", "zeta"), registry.manifest.map { it.name })
        assertEquals(
            listOf("alpha", "mike", "zeta"),
            toolManifest(listOf(zeta, mike, alpha)).map { it.name },
        )
        val entry = registry.manifest.first { it.name == "mike" }
        assertEquals(mike.description, entry.description)
        assertEquals(mike.parametersJsonSchema, entry.parametersJsonSchema)
    }

    @Test
    fun manifestDoesNotDependOnRegistryOrder() {
        val a = FakeTool("a")
        val b = FakeTool("b")

        val first = AgentToolRegistry(listOf(a, b)).manifest
        val second = AgentToolRegistry(listOf(b, a)).manifest

        assertEquals(first, second)
    }

    @Test
    fun findReturnsMatchingToolOrNull() {
        val alpha = FakeTool("alpha")
        val registry = AgentToolRegistry(listOf(alpha, FakeTool("beta")))

        assertSame(alpha, registry.find("alpha"))
        assertNull(registry.find("missing"))
    }

    @Test
    fun executeDispatchesArgsAndReturnsExecutorResult() = runBlocking {
        val expected = ToolResult(AgentToolStatus.OK, "output", meta = mapOf("k" to "v"))
        val tool = FakeTool("echo", result = expected)
        val registry = AgentToolRegistry(listOf(tool))

        val result = registry.execute("echo", """{"value":1}""")

        assertEquals(expected, result)
        assertEquals("""{"value":1}""", tool.lastArgs)
    }

    @Test
    fun unknownToolReturnsError() = runBlocking {
        val registry = AgentToolRegistry(listOf(FakeTool("known")))

        val result = registry.execute("unknown", "{}")

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertEquals("Unknown tool: unknown", result.output)
    }

    @Test
    fun malformedJsonReturnsError() = runBlocking {
        val tool = FakeTool("echo")
        val registry = AgentToolRegistry(listOf(tool))

        val result = registry.execute("echo", "{not json")

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertEquals(true, result.output.startsWith("Invalid arguments JSON: "))
        assertNull(tool.lastArgs)
    }

    @Test
    fun nonObjectJsonReturnsError() = runBlocking {
        val tool = FakeTool("echo")
        val registry = AgentToolRegistry(listOf(tool))

        val result = registry.execute("echo", "[1,2]")

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertEquals(true, result.output.startsWith("Invalid arguments JSON: "))
        assertNull(tool.lastArgs)
    }

    @Test
    fun blankArgsAreTreatedAsEmptyObject() = runBlocking {
        val tool = FakeTool("echo")
        val registry = AgentToolRegistry(listOf(tool))

        registry.execute("echo", "   ")

        assertEquals("{}", tool.lastArgs)
    }

    @Test
    fun executorExceptionReturnsErrorWithoutThrowing() = runBlocking {
        val tool = FakeTool("boom", error = IllegalStateException("boom"))
        val registry = AgentToolRegistry(listOf(tool))

        val result = registry.execute("boom", "{}")

        assertEquals(AgentToolStatus.ERROR, result.status)
        assertEquals("Tool failed: boom", result.output)
    }

    @Test
    fun cancellationExceptionPropagates() {
        val tool = FakeTool("cancel", error = CancellationException("stop"))
        val registry = AgentToolRegistry(listOf(tool))

        val thrown = try {
            runBlocking { registry.execute("cancel", "{}") }
            null
        } catch (e: CancellationException) {
            e
        }

        assertNotNull(thrown)
        assertEquals("stop", thrown?.message)
    }

    private class FakeTool(
        override val name: String,
        private val result: ToolResult = ToolResult(AgentToolStatus.OK, "done"),
        private val error: Exception? = null,
    ) : AgentTool {
        override val description: String = "fake $name"
        override val parametersJsonSchema: String = """{"type":"object"}"""
        var lastArgs: String? = null

        override suspend fun execute(paramsJson: String): ToolResult {
            lastArgs = paramsJson
            error?.let { throw it }
            return result
        }
    }
}
