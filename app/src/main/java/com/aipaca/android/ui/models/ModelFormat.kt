package com.aipaca.android.ui.models

import java.util.Locale
import kotlin.math.round

private const val KIB = 1024L
private const val MIB = KIB * 1024L
private const val GIB = MIB * 1024L
private const val GGUF_EXTENSION = ".gguf"
private const val FALLBACK_NAME = "model"
private val MULTIPLE_DASHES = Regex("-{2,}")
private val EXTENSION_PATTERN = Regex("[A-Za-z0-9]{1,8}")

fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value >= GIB -> formatUnit(value, GIB, "GB")
        value >= MIB && round(value.toDouble() / MIB * 10.0) / 10.0 >= 1024.0 -> formatUnit(value, GIB, "GB")
        value >= MIB -> formatUnit(value, MIB, "MB")
        else -> "$value B"
    }
}

private fun formatUnit(bytes: Long, unit: Long, suffix: String): String {
    val text = String.format(Locale.US, "%.1f", bytes.toDouble() / unit)
    return "${text.removeSuffix(".0")} $suffix"
}

fun sanitizeFileName(displayName: String): String {
    val leaf = displayName.substringAfterLast('/').substringAfterLast('\\')
    val sanitized = leaf
        .replace(' ', '-')
        .filter { it.isAsciiNameChar() }
        .replace(MULTIPLE_DASHES, "-")
    val stem = when {
        sanitized.endsWith(GGUF_EXTENSION, ignoreCase = true) -> sanitized.dropLast(GGUF_EXTENSION.length)
        else -> {
            val dot = sanitized.lastIndexOf('.')
            if (dot >= 0 && EXTENSION_PATTERN.matches(sanitized.substring(dot + 1))) {
                sanitized.substring(0, dot)
            } else {
                sanitized
            }
        }
    }.trim('-', '.')
    return if (stem.isEmpty()) "$FALLBACK_NAME$GGUF_EXTENSION" else "$stem$GGUF_EXTENSION"
}

fun importProgressPercent(read: Long, total: Long): Int {
    if (read <= 0L || total <= 0L) return 0
    return (read * 100L / total).coerceIn(0L, 100L).toInt()
}

private fun Char.isAsciiNameChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '_' || this == '-'
