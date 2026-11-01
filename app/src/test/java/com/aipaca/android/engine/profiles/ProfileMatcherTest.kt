package com.aipaca.android.engine.profiles

import com.aipaca.android.data.device.DeviceDescriptor
import com.aipaca.android.engine.Accelerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileMatcherTest {

    private val sm8650 = DeviceDescriptor("qcom-sm8650/12gb", "sm8650", "qcom", 12, 75)
    private val sm8650Real = DeviceDescriptor("qcom-sm8650/11gb", "sm8650", "qcom", 11, 75)
    private val generic = DeviceDescriptor("generic-arm64/8gb", "generic-arm64", "other", 8, null)

    @Test
    fun gpuAcceleratorPicksOpenClProfile() {
        val result = matcher().match(sm8650, "Qwen3-4B-Q4_0.gguf", "Qwen/Qwen3-4B-GGUF", Accelerator.GPU)

        assertEquals("exact-device+quant", result.reason)
        assertEquals("qwen3-4b/q4_0/sm8650-12gb", result.profile?.doc?.id)
        assertEquals("opencl", result.profile?.doc?.backend)
    }

    @Test
    fun npuAcceleratorPicksHexagonProfile() {
        val result = matcher().match(sm8650, "Qwen3-4B-Q4_0.gguf", "Qwen/Qwen3-4B-GGUF", Accelerator.NPU)

        assertEquals("exact-device+quant", result.reason)
        assertEquals("qwen3-4b/q4_0/sm8650-12gb-npu", result.profile?.doc?.id)
        assertEquals("hexagon", result.profile?.doc?.backend)
    }

    @Test
    fun cpuAcceleratorAvoidsHexagonProfile() {
        val result = matcher().match(sm8650, "Qwen3-4B-Q4_0.gguf", null, Accelerator.CPU)

        assertEquals("qwen3-4b/q4_0/sm8650-12gb", result.profile?.doc?.id)
        assertEquals("opencl", result.profile?.doc?.backend)
    }

    @Test
    fun npuAcceleratorWithoutHexagonProfileFallsBackToDeviceProfile() {
        val result = matcher().match(sm8650, "gemma-3-4b-Q4_0.gguf", null, Accelerator.NPU)

        assertEquals("exact-device+quant", result.reason)
        assertEquals("gemma-3-4b/q4_0/sm8650-12gb", result.profile?.doc?.id)
    }

    @Test
    fun sameSocMatchesWhenDeviceKeyDiffersByRam() {
        val result = matcher().match(sm8650Real, "Qwen3-4B-Q4_0.gguf", null, Accelerator.GPU)

        assertEquals("same-soc+quant", result.reason)
        assertEquals("qwen3-4b/q4_0/sm8650-12gb", result.profile?.doc?.id)
    }

    @Test
    fun sameSocMatchesWhenQuantIsUnknown() {
        val result = matcher().match(sm8650Real, "qwen3-4b-Q4_1.gguf", null, Accelerator.GPU)

        assertEquals("same-soc", result.reason)
        assertEquals("qwen3-4b/q4_0/sm8650-12gb", result.profile?.doc?.id)
    }

    @Test
    fun genericDeviceQuantMatchesWhenExactQuantMissing() {
        val result = matcher().match(sm8650, "Qwen3-4B-Q4_K_M.gguf", null, Accelerator.GPU)

        assertEquals("generic-device+quant", result.reason)
        assertEquals("qwen3-4b/q4_k_m/generic-arm64", result.profile?.doc?.id)
    }

    @Test
    fun exactDeviceMatchesWhenQuantIsUnknown() {
        val result = matcher().match(sm8650, "qwen3-4b-Q4_1.gguf", null, Accelerator.GPU)

        assertEquals("exact-device", result.reason)
        assertEquals("qwen3-4b/q4_0/sm8650-12gb", result.profile?.doc?.id)
    }

    @Test
    fun defaultProfileMatchesUnknownModel() {
        val result = matcher().match(sm8650, "unknown-model-Q4_0.gguf", null, Accelerator.GPU)

        assertEquals("default", result.reason)
        assertEquals("default/generic-arm64", result.profile?.doc?.id)
    }

    @Test
    fun genericDeviceQuantMatchesLlamaProfile() {
        val result = matcher().match(generic, "llama-3.2-3b-IQ4_XS.gguf", null, Accelerator.GPU)

        assertEquals("generic-device+quant", result.reason)
        assertEquals("llama-3.2-3b/iq4_xs/generic-arm64", result.profile?.doc?.id)
    }

    @Test
    fun noMatchWithoutDefaultProfile() {
        val index = ProfileParser.parseIndex(fixture("index.json"))
        val filtered = index.copy(profiles = index.profiles.filterNot { it.family == "default" })
        val catalog = ProfileCatalogLoader.fromJson(
            ProfileParser.json.encodeToString(ProfileIndexDoc.serializer(), filtered),
        ) { path -> resource("/profiles/$path") }

        val result = ProfileMatcher(catalog).match(sm8650, "unknown.gguf", null, Accelerator.GPU)

        assertEquals("no-match", result.reason)
        assertNull(result.profile)
    }

    @Test
    fun npuWithoutFamilyHexagonProfileUsesHexagonDefault() {
        val result = matcherWithHexagonDefault().match(sm8650, "LFM3-1.7B-Q4_K_M.gguf", null, Accelerator.NPU)

        assertEquals("default", result.reason)
        assertEquals("default/hexagon", result.profile?.doc?.id)
    }

    @Test
    fun npuPrefersHexagonDefaultOverCpuFamilyProfile() {
        val result = matcherWithHexagonDefault().match(sm8650, "gemma-3-4b-Q4_0.gguf", null, Accelerator.NPU)

        assertEquals("default/hexagon", result.profile?.doc?.id)
    }

    @Test
    fun npuStillPrefersFamilyHexagonProfile() {
        val result = matcherWithHexagonDefault().match(sm8650, "Qwen3-4B-Q4_0.gguf", null, Accelerator.NPU)

        assertEquals("qwen3-4b/q4_0/sm8650-12gb-npu", result.profile?.doc?.id)
    }

    @Test
    fun cpuIgnoresHexagonDefault() {
        val result = matcherWithHexagonDefault().match(sm8650, "LFM3-1.7B-Q4_K_M.gguf", null, Accelerator.CPU)

        assertEquals("default/generic-arm64", result.profile?.doc?.id)
    }

    private fun matcherWithHexagonDefault(): ProfileMatcher {
        val index = ProfileParser.parseIndex(fixture("index.json"))
        val extended = index.copy(
            profiles = index.profiles + ProfileIndexEntry(
                id = "default/hexagon",
                family = "default",
                device = "generic-arm64/8gb",
                file = "profiles/default-hexagon.json",
            ),
        )
        val catalog = ProfileCatalogLoader.fromJson(
            ProfileParser.json.encodeToString(ProfileIndexDoc.serializer(), extended),
        ) { path -> resource("/profiles/$path") }
        return ProfileMatcher(catalog)
    }

    private fun matcher(): ProfileMatcher =
        ProfileMatcher(ProfileCatalogLoader.fromJson(fixture("index.json")) { path -> resource("/profiles/$path") })

    private fun fixture(path: String): String =
        checkNotNull(resource("/profiles/$path")) { "Missing fixture: /profiles/$path" }

    private fun resource(path: String): String? =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
}
