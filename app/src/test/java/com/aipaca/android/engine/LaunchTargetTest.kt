package com.aipaca.android.engine

import com.aipaca.android.engine.profiles.LaunchDoc
import com.aipaca.android.engine.profiles.ModelMatchDoc
import com.aipaca.android.engine.profiles.ProfileDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTargetTest {

    @Test
    fun cpuForcesZeroOffloadAndDropsHexagonEnv() {
        val doc = hexagonDoc().copy(
            launch = hexagonDoc().launch.copy(
                nGpuLayers = 99,
                env = mapOf(
                    "GGML_HEXAGON_DEVICES" to "HTP0",
                    "GGML_HEXAGON_POWER_MODE" to "sustained_high_performance",
                ),
                extraArgs = listOf("-dev", "HTP0", "--temp", "0.7"),
            ),
        )

        val applied = LaunchTarget.apply(doc, Accelerator.CPU)

        assertEquals("cpu", applied.backend)
        assertEquals(0, applied.launch.nGpuLayers)
        assertEquals(emptyMap<String, String>(), applied.launch.env)
        assertEquals(listOf("--temp", "0.7"), applied.launch.extraArgs)
    }

    @Test
    fun gpuForcesOpenClDeviceAndOffload() {
        val doc = hexagonDoc().copy(
            launch = hexagonDoc().launch.copy(
                nGpuLayers = 0,
                env = mapOf("GGML_HEXAGON_DEVICES" to "HTP0"),
            ),
        )

        val applied = LaunchTarget.apply(doc, Accelerator.GPU)

        assertEquals("opencl", applied.backend)
        assertEquals(99, applied.launch.nGpuLayers)
        assertTrue(applied.launch.env.isEmpty())
        assertEquals(listOf("--device", "GPUOpenCL"), applied.launch.extraArgs)
    }

    @Test
    fun gpuKeepsConfiguredOffloadLayers() {
        val doc = openclDoc().copy(launch = openclDoc().launch.copy(nGpuLayers = 42))

        val applied = LaunchTarget.apply(doc, Accelerator.GPU)

        assertEquals(42, applied.launch.nGpuLayers)
    }

    @Test
    fun npuKeepsHexagonEnvAndForcesHtp0() {
        val doc = openclDoc().copy(launch = openclDoc().launch.copy(nGpuLayers = 5))

        val applied = LaunchTarget.apply(doc, Accelerator.NPU)

        assertEquals("hexagon", applied.backend)
        assertEquals(5, applied.launch.nGpuLayers)
        assertEquals("HTP0", applied.launch.env["GGML_HEXAGON_DEVICES"])
        assertEquals(listOf("--device", "HTP0"), applied.launch.extraArgs)
    }

    @Test
    fun npuSetsHtpArchFromDevice() {
        val applied = LaunchTarget.apply(openclDoc(), Accelerator.NPU, htpArch = 75)

        assertEquals("75", applied.launch.env["GGML_HEXAGON_ARCH"])
    }

    @Test
    fun profileHtpArchWinsOverDeviceTable() {
        val doc = hexagonDoc().copy(
            launch = hexagonDoc().launch.copy(env = mapOf("GGML_HEXAGON_ARCH" to "79")),
        )

        val applied = LaunchTarget.apply(doc, Accelerator.NPU, htpArch = 75)

        assertEquals("79", applied.launch.env["GGML_HEXAGON_ARCH"])
    }

    @Test
    fun htpArchIsNotSetForCpuOrUnknownDevice() {
        assertEquals(null, LaunchTarget.apply(openclDoc(), Accelerator.CPU, htpArch = 75).launch.env["GGML_HEXAGON_ARCH"])
        assertEquals(null, LaunchTarget.apply(openclDoc(), Accelerator.NPU, htpArch = null).launch.env["GGML_HEXAGON_ARCH"])
        assertEquals(emptyMap<String, String>(), LaunchTarget.hexagonDefaults(0))
    }

    @Test
    fun powerModeOverridesProfileOnNpu() {
        val doc = hexagonDoc().copy(
            launch = hexagonDoc().launch.copy(env = mapOf("GGML_HEXAGON_POWER_MODE" to "sustained_high_performance")),
        )

        assertEquals(
            "sustained_high_performance",
            LaunchTarget.apply(doc, Accelerator.NPU).launch.env["GGML_HEXAGON_POWER_MODE"],
        )
        assertEquals(
            "balanced",
            LaunchTarget.apply(doc, Accelerator.NPU, powerMode = NpuPowerMode.BALANCED)
                .launch.env["GGML_HEXAGON_POWER_MODE"],
        )
    }

    @Test
    fun powerModeIsIgnoredOffNpu() {
        val applied = LaunchTarget.apply(openclDoc(), Accelerator.CPU, powerMode = NpuPowerMode.BURST)

        assertEquals(null, applied.launch.env["GGML_HEXAGON_POWER_MODE"])
    }

    @Test
    fun npuHonoursProfileHexagonDevice() {
        val doc = hexagonDoc().copy(
            launch = hexagonDoc().launch.copy(
                env = mapOf("GGML_HEXAGON_DEVICES" to "HTP1"),
                extraArgs = emptyList(),
            ),
        )

        val applied = LaunchTarget.apply(doc, Accelerator.NPU)

        assertEquals("HTP1", applied.launch.env["GGML_HEXAGON_DEVICES"])
        assertEquals(listOf("--device", "HTP1"), applied.launch.extraArgs)
    }

    @Test
    fun npuReplacesProfileDeviceSelection() {
        val doc = openclDoc().copy(
            launch = openclDoc().launch.copy(
                nGpuLayers = 99,
                extraArgs = listOf("--device", "GPUOpenCL", "--top-k", "20"),
            ),
        )

        val applied = LaunchTarget.apply(doc, Accelerator.NPU)

        assertEquals(listOf("--top-k", "20", "--device", "HTP0"), applied.launch.extraArgs)
    }

    private fun hexagonDoc(): ProfileDoc = ProfileDoc(
        schemaVersion = 1,
        id = "test/hexagon",
        backend = "hexagon",
        model = ModelMatchDoc(family = "test", matchPatterns = listOf("test")),
        launch = LaunchDoc(nGpuLayers = 99),
    )

    private fun openclDoc(): ProfileDoc = ProfileDoc(
        schemaVersion = 1,
        id = "test/opencl",
        backend = "opencl",
        model = ModelMatchDoc(family = "test", matchPatterns = listOf("test")),
        launch = LaunchDoc(nGpuLayers = 99),
    )
}
