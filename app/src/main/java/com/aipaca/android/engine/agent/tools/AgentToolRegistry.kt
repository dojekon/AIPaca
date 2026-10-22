package com.aipaca.android.engine.agent.tools

import com.aipaca.android.engine.agent.AgentTool
import com.aipaca.android.engine.agent.AgentToolStatus
import com.aipaca.android.engine.agent.ToolResult
import com.aipaca.android.engine.chat.ToolDef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class AgentToolRegistry(val tools: List<AgentTool>) {
    val manifest: List<ToolDef> = toolManifest(tools)

    fun find(name: String): AgentTool? = tools.firstOrNull { it.name == name }

    suspend fun execute(name: String, argsJson: String): ToolResult {
        val tool = find(name)
            ?: return ToolResult(AgentToolStatus.ERROR, "Unknown tool: $name")

        val normalized = argsJson.ifBlank { "{}" }
        try {
            val parsed = Json.parseToJsonElement(normalized)
            if (parsed !is JsonObject) {
                return ToolResult(AgentToolStatus.ERROR, "Invalid arguments JSON: expected an object")
            }
        } catch (e: Exception) {
            return ToolResult(AgentToolStatus.ERROR, "Invalid arguments JSON: ${e.message}")
        }

        return try {
            tool.execute(normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(AgentToolStatus.ERROR, "Tool failed: ${e.message}")
        }
    }
}

fun toolManifest(tools: List<AgentTool>): List<ToolDef> =
    tools.sortedBy { it.name }.map { tool ->
        ToolDef(
            name = tool.name,
            description = tool.description,
            parametersJsonSchema = tool.parametersJsonSchema,
        )
    }
