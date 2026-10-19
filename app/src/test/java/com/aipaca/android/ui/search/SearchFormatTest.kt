package com.aipaca.android.ui.search

import com.aipaca.android.data.hf.HfGgufFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFormatTest {

    @Test
    fun formatBytesZero() {
        assertEquals("0 B", formatBytes(0))
    }

    @Test
    fun formatBytesNegativeClampsToZero() {
        assertEquals("0 B", formatBytes(-1))
    }

    @Test
    fun formatBytesBelowKilobyte() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(KB - 1))
    }

    @Test
    fun formatBytesKilobytes() {
        assertEquals("1 KB", formatBytes(KB))
        assertEquals("1.5 KB", formatBytes(KB * 3 / 2))
    }

    @Test
    fun formatBytesMegabytes() {
        assertEquals("1 MB", formatBytes(MB))
        assertEquals("512 MB", formatBytes(512 * MB))
        assertEquals("1023 MB", formatBytes(1023 * MB))
    }

    @Test
    fun formatBytesGigabytes() {
        assertEquals("1 GB", formatBytes(GB))
        assertEquals("1.5 GB", formatBytes(GB * 3 / 2))
    }

    @Test
    fun formatBytesRoundsToOneDecimal() {
        assertEquals("1.2 GB", formatBytes(GB * 6 / 5))
        assertEquals("1 GB", formatBytes(GB - 1))
    }

    @Test
    fun formatCountSmall() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
    }

    @Test
    fun formatCountThousands() {
        assertEquals("1K", formatCount(1_000))
        assertEquals("1.5K", formatCount(1_500))
        assertEquals("123.5K", formatCount(123_456))
    }

    @Test
    fun formatCountMillions() {
        assertEquals("2M", formatCount(2_000_000))
        assertEquals("2.5M", formatCount(2_500_000))
    }

    @Test
    fun npuReadyEmptyListIsFalse() {
        assertFalse(npuReady(emptyList()))
    }

    @Test
    fun npuReadyTrueForQ4ZeroFile() {
        assertTrue(npuReady(listOf(file("gemma-3-1b-Q4_0.gguf"))))
        assertTrue(npuReady(listOf(file("model-Q8_0.gguf"), file("gemma-3-1b-Q4_0_4_4.gguf"))))
    }

    @Test
    fun npuReadyFalseWithoutQ4ZeroFile() {
        assertFalse(npuReady(listOf(file("model-Q4_K_M.gguf"), file("model-Q8_0.gguf"))))
        assertFalse(npuReady(listOf(file("model-IQ4_XS.gguf"))))
        assertFalse(npuReady(listOf(file("model-bf16.gguf"))))
    }

    private fun file(name: String): HfGgufFile =
        HfGgufFile(
            fileName = name,
            sizeBytes = 1L,
            sha256 = null,
            downloadUrl = "https://huggingface.co/org/repo/resolve/main/$name",
        )

    private companion object {
        const val KB = 1024L
        const val MB = KB * 1024L
        const val GB = MB * 1024L
    }
}
