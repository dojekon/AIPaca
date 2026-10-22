package com.aipaca.android.engine.agent.tools

import java.io.File
import java.nio.file.Files

class FileWorkspace(private val root: File) : AgentWorkspace {
    private val rootFile: File = root.canonicalFile

    override val description: String = rootFile.absolutePath

    override fun validate(path: String): Result<String> =
        resolveInSandbox(rootFile, path).map { resolved ->
            rootFile.toPath()
                .relativize(resolved.toPath())
                .toString()
                .replace(File.separatorChar, '/')
        }

    override suspend fun list(path: String): Result<List<WorkspaceEntry>> = runCatching {
        val relative = validate(path).getOrThrow()
        val directory = File(rootFile, relative)
        if (!directory.isDirectory) throw IllegalArgumentException("Not a directory: $path")

        directory.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            .orEmpty()
            .map { it.toWorkspaceEntry() }
    }

    override suspend fun walk(path: String, maxEntries: Int): Result<List<WorkspaceEntry>> = runCatching {
        val relative = validate(path).getOrThrow()
        val base = File(rootFile, relative)
        if (!base.isDirectory) throw IllegalArgumentException("Not a directory: $path")

        val collected = mutableListOf<WorkspaceEntry>()
        val pending = ArrayDeque<File>()
        pending.addLast(base)
        while (pending.isNotEmpty() && collected.size < maxEntries) {
            val directory = pending.removeFirst()
            val children = directory.listFiles()
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                .orEmpty()
            for (child in children) {
                if (collected.size >= maxEntries) break
                if (Files.isSymbolicLink(child.toPath())) continue
                collected += child.toWorkspaceEntry()
                if (child.isDirectory) pending.addLast(child)
            }
        }
        collected
    }

    override suspend fun stat(path: String): Result<WorkspaceEntry?> = runCatching {
        val relative = validate(path).getOrThrow()
        val file = File(rootFile, relative)
        if (file.exists()) file.toWorkspaceEntry() else null
    }

    override suspend fun readText(path: String): Result<String> = runCatching {
        val relative = validate(path).getOrThrow()
        File(rootFile, relative).readText(Charsets.UTF_8)
    }

    override suspend fun writeText(path: String, content: String): Result<Unit> = runCatching {
        val relative = validate(path).getOrThrow()
        val file = File(rootFile, relative)
        if (file.isDirectory) throw IllegalArgumentException("Path is a directory: $path")
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        Unit
    }

    override suspend fun exists(path: String): Result<Boolean> = runCatching {
        val relative = validate(path).getOrThrow()
        File(rootFile, relative).exists()
    }

    override suspend fun isDirectory(path: String): Result<Boolean> = runCatching {
        val relative = validate(path).getOrThrow()
        File(rootFile, relative).isDirectory
    }

    private fun File.toWorkspaceEntry(): WorkspaceEntry = WorkspaceEntry(
        path = rootFile.toPath().relativize(toPath().toAbsolutePath().normalize())
            .toString()
            .replace(File.separatorChar, '/'),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = length(),
    )
}
