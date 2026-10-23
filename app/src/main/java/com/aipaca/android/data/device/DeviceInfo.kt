package com.aipaca.android.data.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.roundToInt

data class DeviceDescriptor(
    val deviceKey: String,
    val soc: String,
    val vendor: String,
    val ramGb: Int,
    val htpVersion: Int?,
)

val htpBySoc = mapOf(
    "sm8650" to 75,
    "sm8550" to 73,
    "sm8750" to 79,
    "sm8850" to 81,
)

fun npuSupported(device: DeviceDescriptor): Boolean =
    device.vendor == "qcom" && device.htpVersion != null

fun normalizeSoc(raw: String?): String = raw?.trim()?.lowercase().orEmpty()

fun vendorKey(raw: String?): String {
    val value = raw?.trim()?.lowercase().orEmpty()
    return when {
        value.isEmpty() -> "other"
        value == "qcom" || value == "qti" || value.contains("qualcomm") -> "qcom"
        value.contains("mediatek") -> "mtk"
        value.contains("samsung") -> "samsung"
        value.contains("google") -> "google"
        else -> value
    }
}

fun ramGbOf(bytes: Long): Int = (bytes.toDouble() / GIB).roundToInt()

fun deviceKeyOf(vendor: String, soc: String, ramGb: Int): String = "$vendor-$soc/${ramGb}gb"

private const val GIB = 1024.0 * 1024.0 * 1024.0

object DeviceInfo {

    fun current(context: Context): DeviceDescriptor {
        val soc = normalizeSoc(socRaw().ifBlank { Build.HARDWARE }.ifBlank { Build.BOARD })
        val vendor = vendorKey(vendorRaw().ifBlank { Build.MANUFACTURER })
        val ramGb = ramGbOf(totalMemoryBytes(context))
        return DeviceDescriptor(
            deviceKey = deviceKeyOf(vendor, soc, ramGb),
            soc = soc,
            vendor = vendor,
            ramGb = ramGb,
            htpVersion = htpBySoc[soc],
        )
    }

    private fun socRaw(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""

    private fun vendorRaw(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""

    private fun totalMemoryBytes(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem
    }
}
