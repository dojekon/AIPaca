package com.aipaca.android.ui.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFormatTest {

    @Test
    fun formatBytesZero() {
        assertEquals("0 B", formatBytes(0))
    }

    @Test
    fun formatBytesPlainBytes() {
        assertEquals("1 B", formatBytes(1))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun formatBytesNegativeClampsToZero() {
        assertEquals("0 B", formatBytes(-42))
    }

    @Test
    fun formatBytesJustBelowMegabyteStaysInBytes() {
        assertEquals("1048575 B", formatBytes(MIB - 1))
    }

    @Test
    fun formatBytesExactMegabyte() {
        assertEquals("1 MB", formatBytes(MIB))
    }

    @Test
    fun formatBytesFractionalMegabyte() {
        assertEquals("1.5 MB", formatBytes(MIB * 3 / 2))
    }

    @Test
    fun formatBytes512Megabytes() {
        assertEquals("512 MB", formatBytes(512 * MIB))
    }

    @Test
    fun formatBytesJustBelowGigabyteStaysInMegabytes() {
        assertEquals("1023 MB", formatBytes(1023 * MIB))
    }

    @Test
    fun formatBytesExactGigabyte() {
        assertEquals("1 GB", formatBytes(GIB))
    }

    @Test
    fun formatBytesFractionalGigabyte() {
        assertEquals("1.5 GB", formatBytes(GIB * 3 / 2))
    }

    @Test
    fun formatBytesOneByteBelowGigabyteRoundsUpToGigabyte() {
        assertEquals("1 GB", formatBytes(GIB - 1))
    }

    @Test
    fun sanitizeFileNameReplacesSpaces() {
        assertEquals("My-Model.gguf", sanitizeFileName("My Model.gguf"))
    }

    @Test
    fun sanitizeFileNameStripsPaths() {
        assertEquals(
            "Qwen3-4B-Q4_K_M.gguf",
            sanitizeFileName("/storage/emulated/0/Download/models/Qwen3-4B-Q4_K_M.gguf"),
        )
        assertEquals("model.gguf", sanitizeFileName("C:\\models\\model.gguf"))
    }

    @Test
    fun sanitizeFileNameDropsUnsupportedChars() {
        assertEquals(
            "Qwen3-4B-Q4_0-test.gguf",
            sanitizeFileName("Qwen3-4B (Q4_0) [test].gguf"),
        )
    }

    @Test
    fun sanitizeFileNameCollapsesMultipleDashes() {
        assertEquals("a-b.gguf", sanitizeFileName("a   b.gguf"))
        assertEquals("a-b.gguf", sanitizeFileName("a - - b.gguf"))
    }

    @Test
    fun sanitizeFileNameReplacesForeignExtension() {
        assertEquals("model.gguf", sanitizeFileName("model.bin"))
        assertEquals("model.safetensors.gguf", sanitizeFileName("model.safetensors.ggml"))
    }

    @Test
    fun sanitizeFileNameAddsMissingExtension() {
        assertEquals("model.gguf", sanitizeFileName("model"))
        assertEquals("llama-3.2-3b.gguf", sanitizeFileName("llama-3.2-3b"))
    }

    @Test
    fun sanitizeFileNameNormalizesGgufCase() {
        assertEquals("Q4_K_M-Model.gguf", sanitizeFileName("Q4_K_M Model.GGUF"))
    }

    @Test
    fun sanitizeFileNameFallsBackWhenEmpty() {
        assertEquals("model.gguf", sanitizeFileName(""))
        assertEquals("model.gguf", sanitizeFileName("   "))
        assertEquals("model.gguf", sanitizeFileName("модель.gguf"))
    }

    @Test
    fun importProgressPercentHandlesBounds() {
        assertEquals(0, importProgressPercent(0, 100))
        assertEquals(0, importProgressPercent(50, 0))
        assertEquals(0, importProgressPercent(50, -1))
        assertEquals(0, importProgressPercent(-10, 100))
    }

    @Test
    fun importProgressPercentComputesRatio() {
        assertEquals(50, importProgressPercent(50, 100))
        assertEquals(33, importProgressPercent(1, 3))
        assertEquals(100, importProgressPercent(100, 100))
    }

    @Test
    fun importProgressPercentClampsOverflow() {
        assertEquals(100, importProgressPercent(150, 100))
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = MIB * 1024L
    }
}
