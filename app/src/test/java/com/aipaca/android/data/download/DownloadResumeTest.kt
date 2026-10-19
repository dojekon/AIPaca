package com.aipaca.android.data.download

import com.aipaca.android.data.db.entity.DownloadPartEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadResumeTest {

    private fun part(
        index: Int,
        start: Long,
        end: Long,
        done: Boolean = false,
        downloaded: Long = 0L,
    ) = DownloadPartEntity(
        downloadId = 1L,
        partIndex = index,
        start = start,
        end = end,
        done = done,
        downloadedBytes = downloaded,
    )

    @Test
    fun keepsDoneAndPartialProgressWhenFileMatches() {
        val parts = listOf(
            part(0, 0L, 499L, done = true),
            part(1, 500L, 999L, downloaded = 200L),
        )

        assertEquals(700L, resumableBytes(parts, 1000L, 700L)!!)
    }

    @Test
    fun sumsDonePartsWithFullLength() {
        val parts = listOf(
            part(0, 0L, 499L, done = true),
            part(1, 500L, 999L, done = true, downloaded = 12L),
        )

        assertEquals(1000L, resumableBytes(parts, 1000L, 1000L)!!)
    }

    @Test
    fun restartsWhenFileShorterThanClaimedProgress() {
        val parts = listOf(
            part(0, 0L, 499L, done = true),
            part(1, 500L, 999L, downloaded = 200L),
        )

        assertNull(resumableBytes(parts, 1000L, 650L))
    }

    @Test
    fun restartsWhenCoverageHasGaps() {
        val parts = listOf(
            part(0, 0L, 499L, done = true),
            part(1, 700L, 999L, downloaded = 100L),
        )

        assertNull(resumableBytes(parts, 1000L, 800L))
    }

    @Test
    fun restartsWithoutParts() {
        assertNull(resumableBytes(emptyList(), 1000L, 1000L))
    }

    @Test
    fun clampsDownloadedBytesToPartLength() {
        val parts = listOf(part(0, 0L, 499L, downloaded = 900L))

        assertEquals(500L, resumableBytes(parts, 500L, 500L)!!)
    }

    @Test
    fun allowsZeroProgressWhenFileMissing() {
        val parts = listOf(part(0, 0L, 499L), part(1, 500L, 999L))

        assertNull(resumableBytes(parts, 1000L, 0L))
    }

    @Test
    fun keepsZeroProgressWhenFileExists() {
        val parts = listOf(part(0, 0L, 499L), part(1, 500L, 999L))

        assertEquals(0L, resumableBytes(parts, 1000L, 1000L)!!)
    }
}
