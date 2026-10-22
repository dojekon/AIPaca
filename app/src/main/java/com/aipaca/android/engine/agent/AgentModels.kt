package com.aipaca.android.engine.agent

const val REASONING_BUDGET_MESSAGE =
    "Reasoning budget reached. Stop thinking now and continue with the task: call the needed tool or give the final answer."

object AgentToolStatus {
    const val OK = "ok"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"
}

data class ToolResult(
    val status: String,
    val output: String,
    val truncated: Boolean = false,
    val meta: Map<String, String> = emptyMap(),
)

interface AgentTool {
    val name: String
    val description: String
    val parametersJsonSchema: String
    suspend fun execute(paramsJson: String): ToolResult
}
