package com.aipaca.android.engine

import com.aipaca.android.data.gguf.GgufMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCompatibilityTest {

    private val caps = EngineCapabilities(
        revision = "785322d17f2d51e5185cf996a1f4e46d87640841",
        architectures = listOf("qwen3", "lfm2", "spark2_5"),
        npuWeightTypes = listOf("F16", "Q4_0", "Q8_0", "Q4_K", "Q6_K"),
    )

    @Test
    fun parsesCapabilitiesAndIgnoresUnknownKeys() {
        val parsed = EngineCapabilitiesLoader.parse(
            """{"revision":"abc1234def","architectures":["lfm3"],"npuWeightTypes":["Q4_0"],"extra":1}""",
        )

        assertEquals(listOf("lfm3"), parsed.architectures)
        assertEquals("abc1234", parsed.shortRevision)
        assertTrue(parsed.npuSupportsType("q4_0"))
    }

    @Test
    fun architectureUnknownWhenCapabilitiesMissing() {
        assertNull(EngineCapabilities.UNKNOWN.supportsArchitecture("lfm3"))
        assertTrue(assessModel(meta("lfm3"), EngineCapabilities.UNKNOWN, npuAvailable = true).runnable)
    }

    @Test
    fun unsupportedArchitectureIsNotRunnable() {
        val result = assessModel(meta("lfm3"), caps, npuAvailable = true)

        assertEquals(false, result.architectureSupported)
        assertFalse(result.runnable)
        assertEquals("lfm3", result.architecture)
    }

    @Test
    fun kQuantModelIsNpuCompatibleWhenEngineSupportsTypes() {
        val result = assessModel(
            meta("spark2_5", mapOf("Q4_K" to 900L, "Q6_K" to 100L)),
            caps,
            npuAvailable = true,
        )

        assertEquals(NpuVerdict.SUPPORTED, result.npu)
        assertEquals(1.0, result.npuWeightShare!!, 0.0001)
        assertEquals(Accelerator.NPU, resolveAccelerator(Accelerator.NPU, result))
    }

    @Test
    fun mostlyUnsupportedWeightsFallBackToCpu() {
        val result = assessModel(
            meta("qwen3", mapOf("IQ4_XS" to 700L, "Q8_0" to 300L)),
            caps,
            npuAvailable = true,
        )

        assertEquals(NpuVerdict.UNSUPPORTED_WEIGHTS, result.npu)
        assertEquals(0.3, result.npuWeightShare!!, 0.0001)
        assertEquals(Accelerator.CPU, resolveAccelerator(Accelerator.NPU, result))
    }

    @Test
    fun shareAtThresholdIsSupported() {
        val weights = mapOf("Q4_0" to 80L, "IQ2_XS" to 20L)

        assertEquals(NpuVerdict.SUPPORTED, assessModel(meta("qwen3", weights), caps, npuAvailable = true).npu)
    }

    @Test
    fun noNpuOnDeviceResolvesToCpu() {
        val result = assessModel(meta("qwen3", mapOf("Q4_0" to 1L)), caps, npuAvailable = false)

        assertEquals(NpuVerdict.NO_NPU, result.npu)
        assertEquals(Accelerator.CPU, resolveAccelerator(Accelerator.NPU, result))
    }

    @Test
    fun unknownWeightsKeepRequestedAccelerator() {
        val result = assessModel(meta("qwen3"), caps, npuAvailable = true)

        assertEquals(NpuVerdict.UNKNOWN, result.npu)
        assertEquals(Accelerator.NPU, resolveAccelerator(Accelerator.NPU, result))
        assertEquals(Accelerator.NPU, resolveAccelerator(Accelerator.NPU, ModelCompatibility.UNKNOWN))
    }

    @Test
    fun nonNpuRequestsAreUntouched() {
        val result = assessModel(meta("qwen3", mapOf("IQ4_XS" to 1L)), caps, npuAvailable = true)

        assertEquals(Accelerator.GPU, resolveAccelerator(Accelerator.GPU, result))
        assertEquals(Accelerator.CPU, resolveAccelerator(Accelerator.CPU, result))
    }

    @Test
    fun weightTypeForQuantNames() {
        assertEquals("Q4_K", weightTypeForQuant("Q4_K_M"))
        assertEquals("Q4_K", weightTypeForQuant("Q4_K_XL"))
        assertEquals("Q2_K", weightTypeForQuant("Q2_K_XL"))
        assertEquals("Q8_0", weightTypeForQuant("Q8_0"))
        assertEquals("IQ4_XS", weightTypeForQuant("IQ4_XS"))
        assertEquals("MXFP4", weightTypeForQuant(null, "gpt-oss-20b-MXFP4.gguf"))
        assertNull(weightTypeForQuant(null, "model-bf16.gguf"))
    }

    @Test
    fun defaultContextIsCappedByTrainedContext() {
        assertEquals(2048, defaultContextSize(4096, meta("qwen3", contextLength = 2048)))
        assertEquals(4096, defaultContextSize(4096, meta("qwen3", contextLength = 32768)))
        assertEquals(4096, defaultContextSize(4096, null))
    }

    @Test
    fun acceleratorFromBackend() {
        assertEquals(Accelerator.NPU, Accelerator.fromBackend("hexagon"))
        assertEquals(Accelerator.GPU, Accelerator.fromBackend("opencl"))
        assertNull(Accelerator.fromBackend(null))
    }

    private fun meta(
        architecture: String,
        weightTypes: Map<String, Long> = emptyMap(),
        contextLength: Int? = null,
    ) = GgufMetadata(
        architecture = architecture,
        contextLength = contextLength,
        blockCount = 1,
        embeddingLength = null,
        headCount = emptyList(),
        headCountKv = emptyList(),
        keyLength = null,
        valueLength = null,
        keyLengthSwa = null,
        valueLengthSwa = null,
        slidingWindow = null,
        slidingWindowPattern = null,
        slidingWindowPeriod = null,
        sharedKvLayers = 0,
        feedForwardLength = emptyList(),
        vocabSize = null,
        weightTypes = weightTypes,
    )
}
