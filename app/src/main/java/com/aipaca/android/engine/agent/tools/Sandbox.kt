package com.aipaca.android.engine.agent.tools

import java.io.File

private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")

fun resolveInSandbox(root: File, path: String): Result<File> {
    val rootCanonical = root.canonicalFile

    if (path.isBlank()) return Result.success(rootCanonical)

    if (path.startsWith("/") || path.startsWith("\\") || WINDOWS_ABSOLUTE.matches(path) || File(path).isAbsolute) {
        return Result.failure(IllegalArgumentException("Absolute paths are not allowed: $path"))
    }

    if (hasTraversal(path) || hasTraversal(File(path).normalize().path)) {
        return Result.failure(IllegalArgumentException("Path traversal is not allowed: $path"))
    }

    val target = File(rootCanonical, path).canonicalFile
    val rootPath = rootCanonical.path
    val insideRoot = target == rootCanonical ||
        target.path == rootPath ||
        target.path.startsWith(rootPath + File.separator)

    if (!insideRoot) {
        return Result.failure(IllegalArgumentException("Path escapes the sandbox: $path"))
    }

    return Result.success(target)
}

private fun hasTraversal(path: String): Boolean =
    path.split('/', '\\').any { it == ".." }
