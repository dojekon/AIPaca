package com.aipaca.android.ui.search

import com.aipaca.android.data.hf.HfGgufFile
import com.aipaca.android.data.hf.quantFromFileName
import com.aipaca.android.engine.EngineCapabilities
import com.aipaca.android.engine.npuSupportsQuant
import kotlin.math.roundToLong

private const val KB = 1024L
private const val MB = KB * 1024L
private const val GB = MB * 1024L
private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L

fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    val asKb = value.toDouble() / KB
    val asMb = value.toDouble() / MB
    val asGb = value.toDouble() / GB
    return when {
        value < KB -> "$value B"
        value < MB -> if (roundToTenth(asKb) >= 1024.0) unit(asMb, "MB") else unit(asKb, "KB")
        value < GB -> if (roundToTenth(asMb) >= 1024.0) unit(asGb, "GB") else unit(asMb, "MB")
        else -> unit(asGb, "GB")
    }
}

fun formatCount(count: Long): String {
    val value = count.coerceAtLeast(0L)
    val asK = value.toDouble() / THOUSAND
    val asM = value.toDouble() / MILLION
    return when {
        value < THOUSAND -> value.toString()
        value < MILLION -> if (roundToTenth(asK) >= 1000.0) unit(asM, "M") else "${unitNoSpace(asK)}K"
        else -> "${unitNoSpace(asM)}M"
    }
}

fun npuReadyFileNames(
    files: List<HfGgufFile>,
    capabilities: EngineCapabilities,
    npuAvailable: Boolean,
    architectureSupported: Boolean?,
): Set<String> {
    if (!npuAvailable || architectureSupported == false) return emptySet()
    return files.filter { npuSupportsFile(it.fileName, capabilities) }.mapTo(mutableSetOf()) { it.fileName }
}

fun npuSupportsFile(fileName: String, capabilities: EngineCapabilities): Boolean =
    npuSupportsQuant(quantFromFileName(fileName), fileName, capabilities)

private fun unit(value: Double, suffix: String): String = "${unitNoSpace(value)} $suffix"

private fun unitNoSpace(value: Double): String {
    val tenths = (value * 10.0).roundToLong()
    val whole = tenths / 10L
    val fraction = tenths % 10L
    return if (fraction == 0L) whole.toString() else "$whole.$fraction"
}

private fun roundToTenth(value: Double): Double = (value * 10.0).roundToLong() / 10.0
