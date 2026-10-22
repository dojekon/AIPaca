package com.aipaca.android.engine.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSystemPromptTest {

    private val sandboxRoot = File("/data/user/0/com.aipaca.android/files/agent")
    private val today = "2026-10-19"

    @Test
    fun availableToolsBlockContainsEveryNameAndDescription() {
        val tools = listOf(
            FakeTool("read", "Read a text file from the sandbox."),
            FakeTool("fetch", "Fetch a URL over HTTP."),
            FakeTool("write", "Write text to a sandbox file."),
        )

        val prompt = buildAgentSystemPrompt(tools, today)
        val block = availableToolsBlock(prompt)

        tools.forEach { tool ->
            assertTrue(block.contains("${tool.name}: ${tool.description}"))
        }
        val toolLines = block.lines().filter { it.startsWith("- ") }
        assertEquals(tools.size, toolLines.size)
    }

    @Test
    fun outputIsStableRegardlessOfToolOrder() {
        val tools = listOf(
            FakeTool("zeta", "Last tool."),
            FakeTool("alpha", "First tool."),
            FakeTool("mike", "Middle tool."),
        )

        val forward = buildAgentSystemPrompt(tools, today)
        val reversed = buildAgentSystemPrompt(tools.reversed(), today)

        assertEquals(forward, reversed)
        val block = availableToolsBlock(forward)
        assertEquals(
            listOf("alpha", "mike", "zeta"),
            block.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").substringBefore(":") },
        )
    }

    @Test
    fun lengthStaysUnderBudgetWithManyTools() {
        val tools = (1..15).map { index ->
            FakeTool(
                "tool_${index.toString().padStart(2, '0')}",
                "Perform operation number $index on the sandbox and return a short result.",
            )
        }

        val prompt = buildAgentSystemPrompt(tools, today)

        assertTrue("prompt length was ${prompt.length}", prompt.length < 4000)
    }

    @Test
    fun usesRelativeWorkspaceRootAndToday() {
        val prompt = buildAgentSystemPrompt(listOf(FakeTool("read", "Read.")), today)

        assertFalse(prompt.contains(sandboxRoot.absolutePath))
        assertTrue(prompt.contains("Workspace root: \".\""))
        assertTrue(prompt.contains(today))
    }

    @Test
    fun doesNotLeakParameterSchemas() {
        val schema = """{"type":"object","properties":{"path":{"type":"string"}}}"""
        val tools = listOf(FakeTool("read", "Read.", schema))

        val prompt = buildAgentSystemPrompt(tools, today)

        assertFalse(prompt.contains("\"type\": \"object\""))
        assertFalse(prompt.contains("\"type\":\"object\""))
        assertFalse(prompt.contains(schema))
        assertFalse(prompt.contains("properties"))
    }

    @Test
    fun statesToolsRunWithoutConfirmation() {
        val prompt = buildAgentSystemPrompt(listOf(FakeTool("read", "Read.")), today)

        assertTrue(prompt.contains("without user confirmation"))
    }

    @Test
    fun handlesEmptyToolList() {
        val prompt = buildAgentSystemPrompt(emptyList(), today)
        val block = availableToolsBlock(prompt)

        assertTrue(block.contains("No tools are available"))
        assertTrue(prompt.length < 4000)
    }

    private fun availableToolsBlock(prompt: String): String =
        prompt.substringAfter("Available tools:").substringBefore("Guidelines:").trim()

    private class FakeTool(
        override val name: String,
        override val description: String,
        override val parametersJsonSchema: String = """{"type":"object"}""",
    ) : AgentTool {
        override suspend fun execute(paramsJson: String): ToolResult =
            ToolResult(AgentToolStatus.OK, "")
    }
}
