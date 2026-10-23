package com.aipaca.android.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NpuSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun candidatesPickFastrpcLibs() {
        val picked = NpuSupport.fastrpcCandidates(
            listOf(
                "libcdsprpc.so",
                "libOpenCL.so",
                "libvmmem.so",
                "vendor.qti.hardware.dsp@1.0.so",
                "vendor.qti.hardware.dsp-V1-ndk.so",
                "libc.so",
                "libhidlbase.so",
                "vendor.qti.hardware.display.so",
                "vendor.qti.hardware.camera.so",
            ),
        )
        assertEquals(
            listOf(
                "libcdsprpc.so",
                "libOpenCL.so",
                "libvmmem.so",
                "vendor.qti.hardware.dsp@1.0.so",
                "vendor.qti.hardware.dsp-V1-ndk.so",
            ),
            picked,
        )
    }

    @Test
    fun candidatesIgnoreDisplayPrefix() {
        assertTrue(
            NpuSupport.fastrpcCandidates(listOf("vendor.qti.hardware.display.so")).isEmpty(),
        )
    }

    @Test
    fun libraryPathUsesStageDirWhenStaged() {
        val path = NpuSupport.libraryPathFor(
            nativeDir = "/data/app/pkg/lib/arm64",
            stageDir = File("/data/user/0/pkg/files/npu-libs"),
            staged = listOf("libcdsprpc.so"),
        )
        assertEquals(
            "/data/app/pkg/lib/arm64:/data/user/0/pkg/files/npu-libs",
            path,
        )
    }

    @Test
    fun libraryPathFallsBackToVendorDirsWhenEmpty() {
        val path = NpuSupport.libraryPathFor(
            nativeDir = "/data/app/pkg/lib/arm64",
            stageDir = File("/data/user/0/pkg/files/npu-libs"),
            staged = emptyList(),
        )
        assertEquals("/data/app/pkg/lib/arm64:/vendor/lib64:/system/lib64", path)
    }

    @Test
    fun libraryPathFallsBackWhenStageDirMissing() {
        val path = NpuSupport.libraryPathFor(
            nativeDir = "/data/app/pkg/lib/arm64",
            stageDir = null,
            staged = listOf("libcdsprpc.so"),
        )
        assertEquals("/data/app/pkg/lib/arm64:/vendor/lib64:/system/lib64", path)
    }

    @Test
    fun stageCopiesFastrpcLibs() {
        val vendor = tmp.newFolder("vendor")
        val stage = tmp.newFolder("stage")
        val libs = listOf(
            "libcdsprpc.so" to "cdsp",
            "libOpenCL.so" to "opencl",
            "vendor.qti.hardware.dsp@1.0.so" to "dsp",
            "libc.so" to "system",
        )
        libs.forEach { (name, content) -> File(vendor, name).writeText(content) }

        val staged = NpuSupport.stageLibs(vendor, stage).sorted()

        assertEquals(
            listOf("libOpenCL.so", "libcdsprpc.so", "vendor.qti.hardware.dsp@1.0.so"),
            staged,
        )
        assertEquals("cdsp", File(stage, "libcdsprpc.so").readText())
        assertEquals("dsp", File(stage, "vendor.qti.hardware.dsp@1.0.so").readText())
    }

    @Test
    fun stageSkipsMissingSources() {
        val vendor = tmp.newFolder("vendor2")
        val stage = tmp.newFolder("stage2")
        File(vendor, "libOpenCL.so").writeText("opencl")

        val staged = NpuSupport.stageLibs(vendor, stage)

        assertEquals("libOpenCL.so", staged.single())
        assertFalse(stage.resolve("libcdsprpc.so").exists())
    }

    @Test
    fun stageRefreshesChangedFile() {
        val vendor = tmp.newFolder("vendor3")
        val stage = tmp.newFolder("stage3")
        File(vendor, "libcdsprpc.so").writeText("short")
        val first = NpuSupport.stageLibs(vendor, stage)
        assertEquals(1, first.size)
        File(vendor, "libcdsprpc.so").writeText("a-very-different-length-content")
        val second = NpuSupport.stageLibs(vendor, stage)
        assertTrue(NpuSupport.LIB_CDSP_RPC in second)
        assertEquals("a-very-different-length-content", File(stage, "libcdsprpc.so").readText())
    }

    @Test
    fun stageResultFlags() {
        val result = NpuStageResult(
            dir = File("/tmp"),
            staged = listOf(NpuSupport.LIB_CDSP_RPC, NpuSupport.LIB_OPEN_CL),
        )
        assertTrue(result.fastrpcReady)
        assertTrue(result.openClReady)
        assertFalse(
            NpuStageResult(dir = File("/tmp"), staged = listOf(NpuSupport.LIB_OPEN_CL)).fastrpcReady,
        )
    }

    @Test
    fun sameSizeFileIsRecopiedAfterSystemUpdate() {
        val vendor = tmp.newFolder("vendor4")
        val stage = tmp.newFolder("stage4")
        File(vendor, "libcdsprpc.so").writeText("build-A")
        NpuSupport.stageLibs(vendor, stage, fingerprint = "A")
        File(vendor, "libcdsprpc.so").writeText("build-B")

        NpuSupport.stageLibs(vendor, stage, fingerprint = "A")
        assertEquals("build-A", File(stage, "libcdsprpc.so").readText())

        NpuSupport.stageLibs(vendor, stage, fingerprint = "B")
        assertEquals("build-B", File(stage, "libcdsprpc.so").readText())
        assertEquals("B", File(stage, ".fingerprint").readText())
    }

    @Test
    fun stampIsWrittenOnFirstStage() {
        val vendor = tmp.newFolder("vendor5")
        val stage = tmp.newFolder("stage5")
        File(vendor, "libOpenCL.so").writeText("cl")

        NpuSupport.stageLibs(vendor, stage, fingerprint = "fp")

        assertEquals("fp", File(stage, ".fingerprint").readText())
    }
}
