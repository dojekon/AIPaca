package com.aipaca.android.engine

import com.aipaca.android.engine.profiles.ProfileDoc

object LaunchTarget {

    private const val HEXAGON_ENV_PREFIX = "GGML_HEXAGON_"
    private const val HEXAGON_DEVICES_ENV = "GGML_HEXAGON_DEVICES"
    private const val HEXAGON_ARCH_ENV = "GGML_HEXAGON_ARCH"
    private const val HEXAGON_POWER_ENV = "GGML_HEXAGON_POWER_MODE"
    private const val HEXAGON_DEVICE_DEFAULT = "HTP0"
    private const val OPENCL_DEVICE = "GPUOpenCL"
    private const val OFFLOAD_LAYERS = 99

    private val DEVICE_FLAGS = setOf("--device", "-dev")

    fun hexagonDefaults(htpArch: Int?): Map<String, String> =
        if (htpArch != null && htpArch > 0) mapOf(HEXAGON_ARCH_ENV to htpArch.toString()) else emptyMap()

    fun hexagonPowerOverride(powerMode: NpuPowerMode): Map<String, String> =
        powerMode.envValue?.let { mapOf(HEXAGON_POWER_ENV to it) } ?: emptyMap()

    fun apply(
        doc: ProfileDoc,
        accelerator: Accelerator,
        htpArch: Int? = null,
        powerMode: NpuPowerMode = NpuPowerMode.DEFAULT,
    ): ProfileDoc {
        val launch = doc.launch
        val withoutHexagon = launch.env.filterKeys { !it.startsWith(HEXAGON_ENV_PREFIX) }
        val extraArgs = stripDeviceSelection(launch.extraArgs)
        return when (accelerator) {
            Accelerator.CPU -> doc.copy(
                backend = Accelerator.CPU.profileBackend,
                launch = launch.copy(nGpuLayers = 0, env = withoutHexagon, extraArgs = extraArgs),
            )
            Accelerator.GPU -> doc.copy(
                backend = Accelerator.GPU.profileBackend,
                launch = launch.copy(
                    nGpuLayers = offloadLayers(launch.nGpuLayers),
                    env = withoutHexagon,
                    extraArgs = extraArgs + deviceSelection(OPENCL_DEVICE),
                ),
            )
            Accelerator.NPU -> {
                val hexagonEnv = hexagonDefaults(htpArch).toMutableMap()
                hexagonEnv.putAll(launch.env.filterKeys { it.startsWith(HEXAGON_ENV_PREFIX) })
                hexagonEnv.putAll(hexagonPowerOverride(powerMode))
                val devices = hexagonEnv[HEXAGON_DEVICES_ENV] ?: HEXAGON_DEVICE_DEFAULT
                hexagonEnv[HEXAGON_DEVICES_ENV] = devices
                doc.copy(
                    backend = Accelerator.NPU.profileBackend,
                    launch = launch.copy(
                        nGpuLayers = offloadLayers(launch.nGpuLayers),
                        env = hexagonEnv,
                        extraArgs = extraArgs + deviceSelection(devices),
                    ),
                )
            }
        }
    }

    private fun offloadLayers(configured: Int): Int = if (configured > 0) configured else OFFLOAD_LAYERS

    private fun deviceSelection(name: String): List<String> = listOf("--device", name)

    private fun stripDeviceSelection(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            val next = args.getOrNull(index + 1)
            if (token in DEVICE_FLAGS) {
                index += if (next != null && !next.startsWith("-")) 2 else 1
            } else {
                out += token
                index++
            }
        }
        return out
    }
}
