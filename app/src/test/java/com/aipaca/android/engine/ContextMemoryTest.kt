package com.aipaca.android.engine

import com.aipaca.android.data.gguf.GgufMetadata
import com.aipaca.android.engine.profiles.LaunchDoc
import com.aipaca.android.engine.profiles.ModelMatchDoc
import com.aipaca.android.engine.profiles.ProfileDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMemoryTest {

    private val qwen3 = GgufMetadata(
        architecture = "qwen3",
        contextLength = 32768,
        blockCount = 36,
        embeddingLength = 2560,
        headCount = listOf(32),
        headCountKv = listOf(8),
        keyLength = 128,
        valueLength = 128,
        keyLengthSwa = null,
        valueLengthSwa = null,
        slidingWindow = null,
        slidingWindowPattern = null,
        slidingWindowPeriod = null,
        sharedKvLayers = 0,
        feedForwardLength = listOf(9728),
        vocabSize = 151936,
    )

    private val gemma4 = GgufMetadata(
        architecture = "gemma4",
        contextLength = 131072,
        blockCount = 35,
        embeddingLength = 1536,
        headCount = listOf(8),
        headCountKv = listOf(1),
        keyLength = 512,
        valueLength = 512,
        keyLengthSwa = 256,
        valueLengthSwa = 256,
        slidingWindow = 512,
        slidingWindowPattern = List(35) { it % 5 != 4 },
        slidingWindowPeriod = null,
        sharedKvLayers = 20,
        feedForwardLength = List(35) { if (it < 15) 6144 else 12288 },
        vocabSize = 262144,
    )

    @Test
    fun qwen3KvMatchesLayerFormula() {
        val bytes = kvCacheBytes(qwen3, 4096, "q8_0", "q8_0", ubatch = 128)

        assertEquals((36.0 * 4096 * 8 * 256 * 34 / 32).toLong(), bytes)
    }

    @Test
    fun f16KvIsLargerThanQ80() {
        assertTrue(kvCacheBytes(qwen3, 4096, null, null, 128) > kvCacheBytes(qwen3, 4096, "q8_0", "q8_0", 128))
    }

    @Test
    fun kvGrowsLinearlyWithContextForFullAttention() {
        val small = kvCacheBytes(qwen3, 4096, "q8_0", "q8_0", 128)
        val large = kvCacheBytes(qwen3, 8192, "q8_0", "q8_0", 128)

        assertEquals(small * 2, large)
    }

    @Test
    fun gemma4CountsOnlyNonSharedLayersAndCapsSwa() {
        val bytes = kvCacheBytes(gemma4, 4096, "q8_0", "q8_0", ubatch = 128)
        val global = 3.0 * 4096 * 1 * 1024 * 34 / 32
        val swa = 12.0 * 2304 * 1 * 512 * 34 / 32

        assertEquals((global + swa).toLong(), bytes)
    }

    @Test
    fun swaCacheStopsGrowingPastWindow() {
        val at8k = kvCacheBytes(gemma4, 8192, "q8_0", "q8_0", 128)
        val at16k = kvCacheBytes(gemma4, 16384, "q8_0", "q8_0", 128)
        val globalDelta = (3.0 * 8192 * 1024 * 34 / 32).toLong()

        assertEquals(globalDelta, at16k - at8k)
    }

    @Test
    fun contextIsPaddedTo256Cells() {
        assertEquals(kvCacheBytes(qwen3, 4096, null, null, 128), kvCacheBytes(qwen3, 4000, null, null, 128))
    }

    @Test
    fun computeBufferScalesWithUbatch() {
        assertTrue(computeBufferBytes(qwen3, 512) > computeBufferBytes(qwen3, 128))
    }

    @Test
    fun computeBufferMatchesS24uCalibration() {
        val mib = 1024L * 1024
        assertEquals(420L, computeBufferBytes(qwen3, 128) / mib)
        assertEquals(480L, computeBufferBytes(qwen3, 512) / mib)
    }

    @Test
    fun totalSumsParts() {
        val estimate = estimateContextMemory(qwen3, 2_000_000_000L, 4096, "q8_0", "q8_0", 128)

        assertEquals(estimate.weightsBytes + estimate.kvBytes + estimate.computeBytes, estimate.totalBytes)
    }

    @Test
    fun fitLevels() {
        assertEquals(MemoryFit.FITS, memoryFit(700, 1000))
        assertEquals(MemoryFit.TIGHT, memoryFit(900, 1000))
        assertEquals(MemoryFit.TOO_BIG, memoryFit(1100, 1000))
        assertEquals(MemoryFit.TOO_BIG, memoryFit(1, 0))
    }

    @Test
    fun npuSessionWindow() {
        assertFalse(exceedsNpuSession(ContextMemoryEstimate(4096, 2_400_000_000L, 300_000_000L, 0)))
        assertTrue(exceedsNpuSession(ContextMemoryEstimate(4096, 3_100_000_000L, 300_000_000L, 0)))
    }

    @Test
    fun sliderBoundsAndSnapping() {
        assertEquals(CONTEXT_SLIDER_CAP, contextSliderMax(gemma4))
        assertEquals(CONTEXT_SLIDER_CAP, contextSliderMax(null))
        assertEquals(4096, snapContext(4200f))
        assertEquals(4608, snapContext(4400f))
        assertEquals(CONTEXT_SLIDER_MIN, snapContext(10f))
    }

    @Test
    fun contextOverrideAppliesOnlyWhenSet() {
        val doc = ProfileDoc(
            id = "p",
            backend = "cpu",
            model = ModelMatchDoc(family = "f"),
            launch = LaunchDoc(nCtx = 4096),
        )

        assertEquals(4096, doc.withContextSize(null).launch.nCtx)
        assertEquals(8192, doc.withContextSize(8192).launch.nCtx)
    }
}

class MemoryLogLineTest {

    @Test
    fun memoryLinesAreRecognized() {
        assertTrue(isMemoryLogLine("load_tensors:   CPU_Mapped model buffer size =  2255.00 MiB"))
        assertTrue(isMemoryLogLine("llama_kv_cache: size =  306.00 MiB (  4096 cells,  36 layers,  4/1 seqs)"))
        assertTrue(isMemoryLogLine("sched_reserve:      HTP0 compute buffer size =   158.00 MiB"))
        assertTrue(isMemoryLogLine("graph splits = 3"))
        assertFalse(isMemoryLogLine("srv  update_slots: all slots are idle"))
    }
}
