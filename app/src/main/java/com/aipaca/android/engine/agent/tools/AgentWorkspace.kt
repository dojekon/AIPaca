package com.aipaca.android.engine.agent.tools

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

interface AgentWorkspace {
    val description: String

    fun validate(path: String): Result<String>

    suspend fun list(path: String): Result<List<WorkspaceEntry>>

    suspend fun walk(path: String, maxEntries: Int = 2000): Result<List<WorkspaceEntry>>

    suspend fun stat(path: String): Result<WorkspaceEntry?>

    suspend fun readText(path: String): Result<String>

    suspend fun writeText(path: String, content: String): Result<Unit>

    suspend fun exists(path: String): Result<Boolean>

    suspend fun isDirectory(path: String): Result<Boolean>
}
