package com.aipaca.android.data.hf

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HfModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun detailsFixtureParsesAndMapsGgufFiles() {
        val details = json.decodeFromString<HfModelDetails>(DETAILS_JSON)

        assertEquals("bartowski/Llama-3.2-1B-Instruct-GGUF", details.id)
        assertEquals(true, details.gated)
        assertEquals(listOf("gguf", "llama"), details.tags)

        val files = details.ggufFiles("bartowski/Llama-3.2-1B-Instruct-GGUF")

        assertEquals(
            listOf(
                "Llama-3.2-1B-Instruct-Q4_K_M-split-00001-of-00002.GGUF",
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                "Llama-3.2-1B-Instruct-Q8_0.gguf",
            ),
            files.map { it.fileName },
        )

        val split = files[0]
        assertEquals(0L, split.sizeBytes)
        assertNull(split.sha256)
        assertEquals(
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M-split-00001-of-00002.GGUF",
            split.downloadUrl,
        )

        val q4 = files[1]
        assertEquals(887000000L, q4.sizeBytes)
        assertNull(q4.sha256)

        val q8 = files[2]
        assertEquals(1350000000L, q8.sizeBytes)
        assertEquals("a1b2c3d4e5f6", q8.sha256)
    }

    @Test
    fun ggufFilesIsEmptyWithoutSiblings() {
        val details = json.decodeFromString<HfModelDetails>("""{"id": "org/repo"}""")

        assertEquals(emptyList<HfGgufFile>(), details.ggufFiles("org/repo"))
    }

    @Test
    fun summaryFixtureParsesAllFields() {
        val summary = json.decodeFromString<HfModelSummary>(SUMMARY_JSON)

        assertEquals("bartowski/Llama-3.2-1B-Instruct-GGUF", summary.id)
        assertEquals(123456L, summary.downloads)
        assertEquals(42L, summary.likes)
        assertEquals(false, summary.gated)
        assertEquals(listOf("gguf"), summary.tags)
    }

    @Test
    fun summaryMissingOptionalFieldsStayNull() {
        val summary = json.decodeFromString<HfModelSummary>("""{"id": "org/repo"}""")

        assertNull(summary.downloads)
        assertNull(summary.likes)
        assertNull(summary.gated)
        assertEquals(emptyList<String>(), summary.tags)
    }

    @Test
    fun gatedStringValuesParseAsGated() {
        val manual = json.decodeFromString<HfModelSummary>("""{"id": "meta-llama/Llama-3.1-8B", "gated": "manual"}""")
        val auto = json.decodeFromString<HfModelDetails>("""{"id": "meta-llama/Llama-3.1-8B", "gated": "auto"}""")

        assertEquals(true, manual.gated)
        assertEquals(true, auto.gated)
    }

    @Test
    fun ggufFilesReadsSizesInlineFromSiblingsWithoutBlobs() {
        val details = json.decodeFromString<HfModelDetails>(SIBLINGS_INLINE_JSON)

        val files = details.ggufFiles("Qwen/Qwen3-0.6B-GGUF")

        assertEquals(1, files.size)
        val file = files[0]
        assertEquals("Qwen3-0.6B-Q8_0.gguf", file.fileName)
        assertEquals(639446688L, file.sizeBytes)
        assertEquals("9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", file.sha256)
    }

    @Test
    fun ggufFilesPrefersSiblingSizeOverBlobLookup() {
        val siblingOnly = json.decodeFromString<HfModelDetails>(
            """{"id": "org/repo", "siblings": [{"rfilename": "model-Q4_0.gguf", "size": 123456789}]}""",
        )
        val file = siblingOnly.ggufFiles("org/repo").single()
        assertEquals(123456789L, file.sizeBytes)
        assertNull(file.sha256)
    }

    @Test
    fun quantFromFileNameFindsQuantTokens() {
        assertEquals("Q4_K_M", quantFromFileName("Meta-Llama-3-8B-Instruct-Q4_K_M.gguf"))
        assertEquals("Q8_0", quantFromFileName("llama-2-7b-q8_0.GGUF"))
        assertEquals("Q6_K", quantFromFileName("x-q6_k.gguf"))
        assertEquals("Q2_K_XL", quantFromFileName("qwen3-4b-q2_k_xl.gguf"))
    }

    @Test
    fun quantFromFileNameKeepsMultiTokenArmQuants() {
        assertEquals("Q4_0_8_8", quantFromFileName("gemma-2b-Q4_0_8_8.gguf"))
        assertEquals("Q4_0_4_4", quantFromFileName("gemma-2b-Q4_0_4_4.gguf"))
    }

    @Test
    fun quantFromFileNameHandlesIqVariants() {
        assertEquals("IQ4_XS", quantFromFileName("deepseek-r1-IQ4_XS.gguf"))
        assertEquals("IQ3_M", quantFromFileName("model-iq3_m.gguf"))
        assertEquals("IQ2_XXS", quantFromFileName("model-IQ2_XXS.gguf"))
    }

    @Test
    fun quantFromFileNameFindsQuantInTheMiddle() {
        assertEquals("Q4_K_M", quantFromFileName("Qwen2.5-7B-Instruct-Q4_K_M-test.gguf"))
    }

    @Test
    fun quantFromFileNameUsesLastMatch() {
        assertEquals("Q8_0", quantFromFileName("llama-q4_k_m-q8_0.gguf"))
    }

    @Test
    fun quantFromFileNameReturnsNullWithoutQuant() {
        assertNull(quantFromFileName("llama-3-8b-instruct.gguf"))
        assertNull(quantFromFileName("README.md"))
        assertNull(quantFromFileName("model-bf16.gguf"))
    }

    @Test
    fun resolveUrlBuildsHuggingFaceResolveLinks() {
        assertEquals(
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            resolveUrl("bartowski/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
        )
        assertEquals(
            "https://huggingface.co/org/repo/resolve/v2.0/model.gguf",
            resolveUrl("org/repo", "model.gguf", revision = "v2.0"),
        )
    }

    @Test
    fun ramFitForClassifiesBySizeBoundaries() {
        val gib = 1024L * 1024L * 1024L
        assertEquals(RamFit.ENOUGH, ramFitFor(-1L))
        assertEquals(RamFit.ENOUGH, ramFitFor(5L * gib - 1))
        assertEquals(RamFit.MAYBE, ramFitFor(5L * gib))
        assertEquals(RamFit.MAYBE, ramFitFor(9L * gib - 1))
        assertEquals(RamFit.TOO_BIG, ramFitFor(9L * gib))
        assertEquals(RamFit.TOO_BIG, ramFitFor(40L * gib))
    }
}

private val SIBLINGS_INLINE_JSON = """
    {
      "id": "Qwen/Qwen3-0.6B-GGUF",
      "siblings": [
        { "rfilename": ".gitattributes", "blobId": "e1d1e4f5", "size": 1808 },
        {
          "rfilename": "Qwen3-0.6B-Q8_0.gguf",
          "blobId": "11bf194c",
          "size": 639446688,
          "lfs": { "sha256": "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", "size": 639446688, "pointerSize": 134 }
        },
        { "rfilename": "README.md", "blobId": "249fd407", "size": 6236 }
      ]
    }
""".trimIndent()

private val DETAILS_JSON = """
    {
      "id": "bartowski/Llama-3.2-1B-Instruct-GGUF",
      "gated": true,
      "private": false,
      "tags": ["gguf", "llama"],
      "siblings": [
        { "rfilename": "Llama-3.2-1B-Instruct-Q8_0.gguf" },
        { "rfilename": "Llama-3.2-1B-Instruct-Q4_K_M.gguf" },
        { "rfilename": "Llama-3.2-1B-Instruct-Q4_K_M-split-00001-of-00002.GGUF" },
        { "rfilename": "README.md" },
        { "rfilename": "config.json" }
      ],
      "blobs": [
        {
          "rfilename": "Llama-3.2-1B-Instruct-Q8_0.gguf",
          "size": 1900000000,
          "lfs": { "oid": "a1b2c3d4e5f6", "size": 1350000000, "pointerSize": 135 }
        },
        {
          "rfilename": "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
          "size": 887000000
        },
        { "rfilename": "README.md", "size": 4096 }
      ]
    }
""".trimIndent()

private val SUMMARY_JSON = """
    {
      "id": "bartowski/Llama-3.2-1B-Instruct-GGUF",
      "downloads": 123456,
      "likes": 42,
      "gated": false,
      "tags": ["gguf"],
      "private": false,
      "library_name": "gguf"
    }
""".trimIndent()
