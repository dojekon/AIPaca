package com.aipaca.android.ui.workspace

import com.aipaca.android.engine.agent.tools.WorkspaceEntry

const val PREVIEW_MAX_BYTES = 256 * 1024
private const val BINARY_PROBE_BYTES = 8 * 1024

fun sortWorkspaceEntries(entries: List<WorkspaceEntry>): List<WorkspaceEntry> =
    entries.sortedWith(compareBy<WorkspaceEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })

fun parentPath(path: String): String =
    path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")

fun childPath(dir: String, name: String): String =
    if (dir.isEmpty()) name else "${dir.trimEnd('/')}/$name"

fun sanitizeImportName(raw: String?): String {
    val name = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        .replace(Regex("[\\u0000-\\u001f]"), "")
    return when (name) {
        "", ".", ".." -> "imported.txt"
        else -> name
    }
}

fun uniqueName(existing: Set<String>, name: String): String {
    if (name !in existing) return name
    val stem = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    var index = 1
    while ("$stem-$index$extension" in existing) index++
    return "$stem-$index$extension"
}

fun looksBinary(bytes: ByteArray): Boolean =
    bytes.take(BINARY_PROBE_BYTES).any { it == 0.toByte() }
