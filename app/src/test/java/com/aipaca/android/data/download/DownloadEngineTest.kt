package com.aipaca.android.data.download

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val engine = DownloadEngine()

    @Test
    fun planPartsSingleConnectionCoversWholeFile() {
        assertEquals(listOf(PartSpec(0, 0L, 999L)), engine.planParts(1000L, 1))
    }

    @Test
    fun planPartsDividesEvenly() {
        val parts = engine.planParts(100L, 4)

        assertEquals(
            listOf(
                PartSpec(0, 0L, 24L),
                PartSpec(1, 25L, 49L),
                PartSpec(2, 50L, 74L),
                PartSpec(3, 75L, 99L),
            ),
            parts,
        )
    }

    @Test
    fun planPartsPutsRemainderIntoLastPart() {
        val parts = engine.planParts(101L, 4)

        assertEquals(25L, parts[0].length)
        assertEquals(25L, parts[1].length)
        assertEquals(25L, parts[2].length)
        assertEquals(26L, parts[3].length)
        assertEquals(0L, parts.first().start)
        assertEquals(100L, parts.last().endInclusive)
    }

    @Test
    fun planPartsCoversTotalWhenTotalSmallerThanConnections() {
        val parts = engine.planParts(3L, 8)

        assertEquals(
            listOf(
                PartSpec(0, 0L, 0L),
                PartSpec(1, 1L, 1L),
                PartSpec(2, 2L, 2L),
            ),
            parts,
        )
    }

    @Test
    fun planPartsClampsConnectionsToEight() {
        assertEquals(8, engine.planParts(1000L, 9).size)
    }

    @Test
    fun planPartsClampsConnectionsToAtLeastOne() {
        assertEquals(listOf(PartSpec(0, 0L, 999L)), engine.planParts(1000L, 0))
        assertEquals(listOf(PartSpec(0, 0L, 999L)), engine.planParts(1000L, -4))
    }

    @Test
    fun planPartsReturnsEmptyForNonPositiveTotal() {
        assertTrue(engine.planParts(0L, 4).isEmpty())
        assertTrue(engine.planParts(-10L, 4).isEmpty())
    }

    @Test
    fun planPartsAreContiguousAndCoverWholeFile() {
        val parts = engine.planParts(10_000L, 7)

        var expected = 0L
        for (part in parts) {
            assertEquals(expected, part.start)
            expected = part.endInclusive + 1L
        }
        assertEquals(10_000L, expected)
    }

    @Test
    fun rangeHeaderFormatsInclusiveRange() {
        assertEquals("bytes=100-199", engine.rangeHeader(PartSpec(1, 100L, 199L)))
    }

    @Test
    fun rangeHeaderShiftsStartByResumeOffset() {
        assertEquals("bytes=140-199", engine.rangeHeader(PartSpec(1, 100L, 199L), 40L))
        assertEquals("bytes=100-199", engine.rangeHeader(PartSpec(1, 100L, 199L), -5L))
    }

    @Test
    fun speedEmaFirstMeasurementEqualsRate() {
        val ema = SpeedEma()

        assertEquals(1000.0, ema.add(1000L, 1000L), EPSILON)
    }

    @Test
    fun speedEmaAppliesAlphaToLaterMeasurements() {
        val ema = SpeedEma(alpha = 0.5)

        ema.add(1000L, 1000L)

        assertEquals(1500.0, ema.add(2000L, 1000L), EPSILON)
    }

    @Test
    fun speedEmaUsesDefaultAlphaOfZeroPointThree() {
        val ema = SpeedEma()

        ema.add(2000L, 2000L)

        val expected = 0.3 * 2000.0 + 0.7 * 1000.0
        assertEquals(expected, ema.add(2000L, 1000L), EPSILON)
    }

    @Test
    fun speedEmaWithZeroDurationReturnsLastRate() {
        val ema = SpeedEma()

        assertEquals(0.0, ema.add(500L, 0L), EPSILON)

        ema.add(1000L, 1000L)

        assertEquals(1000.0, ema.add(500L, 0L), EPSILON)
        assertEquals(1000.0, ema.add(500L, 0L), EPSILON)
    }

    @Test
    fun speedEmaIgnoresNegativeDelta() {
        val ema = SpeedEma()

        ema.add(1000L, 1000L)

        assertEquals(1000.0, ema.add(-400L, 1000L), EPSILON)
    }

    @Test
    fun verifySha256AcceptsKnownDigest() = runBlocking {
        val file = tempFolder.newFile("model.gguf")
        file.writeText("hello")

        assertTrue(
            engine.verifySha256(
                file,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ),
        )
    }

    @Test
    fun verifySha256RejectsOtherDigest() = runBlocking {
        val file = tempFolder.newFile("model.gguf")
        file.writeText("hello")

        assertFalse(engine.verifySha256(file, "00".repeat(32)))
    }

    @Test
    fun verifySha256IgnoresCaseAndSurroundingSpaces() = runBlocking {
        val file = tempFolder.newFile("model.gguf")
        file.writeText("hello")

        assertTrue(
            engine.verifySha256(
                file,
                "  2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824 ",
            ),
        )
    }

    @Test
    fun verifySha256ReturnsFalseForMissingFile() = runBlocking {
        assertFalse(engine.verifySha256(File(tempFolder.root, "absent.gguf"), "00"))
    }

    @Test
    fun sanitizeFileNameReplacesForbiddenCharacters() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i.gguf",
            sanitizeFileName("a/b\\c:d*e?f\"g<h>i.gguf"),
        )
    }

    @Test
    fun sanitizeFileNameKeepsAllowedCharacters() {
        assertEquals("Qwen3-4B-Q4_K_M.gguf", sanitizeFileName("Qwen3-4B-Q4_K_M.gguf"))
    }

    companion object {

        private const val EPSILON = 0.0001
    }
}
