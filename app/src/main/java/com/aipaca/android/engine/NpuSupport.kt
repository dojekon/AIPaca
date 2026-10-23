package com.aipaca.android.engine

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

data class NpuStageResult(
    val dir: File,
    val staged: List<String>,
) {
    val fastrpcReady: Boolean = staged.any { it == NpuSupport.LIB_CDSP_RPC }
    val openClReady: Boolean = staged.any { it == NpuSupport.LIB_OPEN_CL }
}

object NpuSupport {

    const val LIB_CDSP_RPC = "libcdsprpc.so"
    const val LIB_OPEN_CL = "libOpenCL.so"
    const val LIB_VMMEM = "libvmmem.so"

    private const val STAGE_DIR_NAME = "npu-libs"
    private const val STAMP_FILE = ".fingerprint"
    private const val VENDOR_DIR = "/vendor/lib64"
    private const val DSP_CLIENT_PREFIX = "vendor.qti.hardware.dsp"
    private const val LEGACY_VENDOR_DIR = "/vendor/lib64"
    private const val LEGACY_SYSTEM_DIR = "/system/lib64"

    fun fastrpcCandidates(entries: List<String>): List<String> =
        entries.filter { it in REQUIRED_NAMES || it.startsWith(DSP_CLIENT_PREFIX) }

    fun libraryPathFor(nativeDir: String, stageDir: File?, staged: List<String>): String =
        if (stageDir != null && staged.isNotEmpty()) {
            "$nativeDir:$stageDir"
        } else {
            "$nativeDir:$LEGACY_VENDOR_DIR:$LEGACY_SYSTEM_DIR"
        }

    fun stage(context: Context): NpuStageResult =
        NpuStageResult(
            dir = File(context.filesDir, STAGE_DIR_NAME),
            staged = stageLibs(File(VENDOR_DIR), File(context.filesDir, STAGE_DIR_NAME), Build.FINGERPRINT.orEmpty()),
        )

    fun stageLibs(vendorDir: File, stageDir: File, fingerprint: String = ""): List<String> {
        if (!stageDir.isDirectory && !stageDir.mkdirs()) return emptyList()
        val stamp = File(stageDir, STAMP_FILE)
        val sameBuild = stamp.isFile && stamp.readText() == fingerprint
        val staged = mutableListOf<String>()
        var copyFailed = false
        for (name in fastrpcCandidates(vendorDir.list().orEmpty().toList())) {
            val source = File(vendorDir, name)
            if (!source.isFile) continue
            val target = File(stageDir, name)
            val upToDate = sameBuild && target.isFile && target.length() == source.length()
            if (upToDate || copy(source, target)) {
                staged += name
            } else {
                copyFailed = true
            }
        }
        if (!copyFailed) writeStamp(stamp, fingerprint)
        return staged
    }

    private fun writeStamp(stamp: File, fingerprint: String) {
        try {
            stamp.writeText(fingerprint)
        } catch (_: IOException) {
            stamp.delete()
        }
    }

    private fun copy(source: File, target: File): Boolean = try {
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        true
    } catch (_: IOException) {
        target.delete()
        false
    }

    private val REQUIRED_NAMES = setOf(LIB_CDSP_RPC, LIB_OPEN_CL, LIB_VMMEM)
}
