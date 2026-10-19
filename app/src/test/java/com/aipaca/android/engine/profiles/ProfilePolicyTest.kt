package com.aipaca.android.engine.profiles

import com.aipaca.android.engine.Accelerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePolicyTest {

    @Test
    fun isHexagonDetectsHexagonBackend() {
        assertTrue(ProfilePolicy.isHexagon(doc("qwen-npu", "hexagon")))
    }

    @Test
    fun isHexagonFalseForOtherBackends() {
        assertFalse(ProfilePolicy.isHexagon(doc("qwen-cpu", "cpu")))
        assertFalse(ProfilePolicy.isHexagon(doc("qwen-opencl", "opencl")))
    }

    @Test
    fun filterKeepsHexagonWhenNpuSelected() {
        val profiles = listOf(
            profile("qwen-cpu", backend = "cpu"),
            profile("qwen-npu", backend = "hexagon"),
        )

        assertEquals(profiles, ProfilePolicy.filterForSettings(profiles, Accelerator.NPU))
    }

    @Test
    fun filterDropsHexagonWhenNpuNotSelected() {
        val cpu = profile("qwen-cpu", backend = "cpu")
        val hexagon = profile("qwen-npu", backend = "hexagon")
        val opencl = profile("qwen-opencl", backend = "opencl")

        assertEquals(listOf(cpu, opencl), ProfilePolicy.filterForSettings(listOf(cpu, hexagon, opencl), Accelerator.GPU))
        assertEquals(listOf(cpu, opencl), ProfilePolicy.filterForSettings(listOf(cpu, hexagon, opencl), Accelerator.CPU))
    }

    @Test
    fun cpuFallbackWorksForFailedOpenclDoc() {
        val failed = doc("qwen-opencl", "opencl", patterns = listOf("qwen3.?4b"))
        val match = profile("qwen-cpu", backend = "cpu", patterns = listOf("qwen3.?4b"))

        assertEquals(match, ProfilePolicy.cpuFallback(listOf(match), failed))
    }

    @Test
    fun cpuFallbackMatchesPatternOverlap() {
        val failed = doc("qwen-npu", "hexagon", patterns = listOf("qwen3.?4b"))
        val other = profile("llama-cpu", backend = "cpu", patterns = listOf("llama3"))
        val match = profile("qwen-cpu", backend = "cpu", patterns = listOf("qwen3.?4b"))

        assertEquals(match, ProfilePolicy.cpuFallback(listOf(other, match), failed))
    }

    @Test
    fun cpuFallbackMatchesQuantOverlapIgnoringCase() {
        val failed = doc("qwen-npu", "hexagon", quants = listOf("Q4_0"))
        val other = profile("llama-cpu", backend = "cpu", quants = listOf("Q8_0"))
        val match = profile("qwen-cpu", backend = "cpu", quants = listOf("q4_0"))

        assertEquals(match, ProfilePolicy.cpuFallback(listOf(other, match), failed))
    }

    @Test
    fun cpuFallbackReturnsFirstMatch() {
        val failed = doc("qwen-npu", "hexagon", patterns = listOf("qwen3"))
        val first = profile("qwen-cpu-a", backend = "cpu", patterns = listOf("qwen3"))
        val second = profile("qwen-cpu-b", backend = "cpu", patterns = listOf("qwen3"))

        assertEquals(first, ProfilePolicy.cpuFallback(listOf(first, second), failed))
    }

    @Test
    fun cpuFallbackFallsBackToDefaultGeneric() {
        val failed = doc("qwen-npu", "hexagon", patterns = listOf("qwen3"), quants = listOf("Q4_0"))
        val other = profile("llama-cpu", backend = "cpu", patterns = listOf("llama3"), quants = listOf("Q8_0"))
        val generic = profile(
            "default/generic-arm64",
            backend = "cpu",
            patterns = listOf(".*"),
            sourcePath = "profiles/default-generic.json",
        )

        assertEquals(generic, ProfilePolicy.cpuFallback(listOf(other, generic), failed))
    }

    @Test
    fun cpuFallbackReturnsNullWithoutDefaultGeneric() {
        val failed = doc("qwen-npu", "hexagon", patterns = listOf("qwen3"))
        val other = profile("llama-cpu", backend = "cpu", patterns = listOf("llama3"))

        assertNull(ProfilePolicy.cpuFallback(listOf(other), failed))
    }

    @Test
    fun cpuFallbackSkipsHexagonProfiles() {
        val failed = doc("qwen-npu", "hexagon", patterns = listOf("qwen3"))
        val otherHexagon = profile("qwen-npu-alt", backend = "hexagon", patterns = listOf("qwen3"))

        assertNull(ProfilePolicy.cpuFallback(listOf(otherHexagon), failed))
    }

    @Test
    fun cpuFallbackReturnsNullWhenProfilesEmpty() {
        assertNull(ProfilePolicy.cpuFallback(emptyList(), doc("qwen-npu", "hexagon")))
    }

    private fun profile(
        id: String,
        backend: String,
        patterns: List<String> = emptyList(),
        quants: List<String> = emptyList(),
        sourcePath: String = "profiles/$id.json",
    ): LoadedProfile = LoadedProfile(doc(id, backend, patterns, quants), sourcePath)

    private fun doc(
        id: String,
        backend: String,
        patterns: List<String> = emptyList(),
        quants: List<String> = emptyList(),
    ): ProfileDoc = ProfileDoc(
        id = id,
        backend = backend,
        model = ModelMatchDoc(
            family = id.substringBefore('/'),
            matchPatterns = patterns,
            quants = quants,
        ),
        launch = LaunchDoc(),
    )
}
