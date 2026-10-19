package com.aipaca.android.engine

import java.util.Locale

enum class ThermalLevel { NONE, MODERATE, SEVERE, CRITICAL }

const val RAM_RESERVE_BYTES = 384L * 1024L * 1024L
const val DISK_RESERVE_BYTES = 128L * 1024L * 1024L

private const val GIB = 1024L * 1024L * 1024L

fun thermalLevelFor(status: Int): ThermalLevel = when {
    status <= 1 -> ThermalLevel.NONE
    status == 2 -> ThermalLevel.MODERATE
    status == 3 -> ThermalLevel.SEVERE
    else -> ThermalLevel.CRITICAL
}

fun ramRequiredBytes(fileBytes: Long): Long = fileBytes.coerceAtLeast(0L) + RAM_RESERVE_BYTES

fun hasEnoughRam(fileBytes: Long, availBytes: Long): Boolean =
    availBytes >= ramRequiredBytes(fileBytes)

fun diskRequiredBytes(totalBytes: Long): Long =
    if (totalBytes <= 0L) 0L else totalBytes + totalBytes / 10L + DISK_RESERVE_BYTES

fun hasEnoughDisk(freeBytes: Long, totalBytes: Long): Boolean =
    totalBytes <= 0L || freeBytes >= diskRequiredBytes(totalBytes)

fun formatGigabytes(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f", bytes.coerceAtLeast(0L).toDouble() / GIB)
