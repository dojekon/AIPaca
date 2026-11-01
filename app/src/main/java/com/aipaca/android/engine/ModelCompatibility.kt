package com.aipaca.android.engine

import com.aipaca.android.data.gguf.GgufMetadata

enum class NpuVerdict { SUPPORTED, NO_NPU, UNSUPPORTED_WEIGHTS, UNKNOWN }

data class ModelCompatibility(
    val architecture: String?,
    val architectureSupported: Boolean?,
    val npu: NpuVerdict,
    val npuWeightShare: Double?,
) {
    val runnable: Boolean get() = architectureSupported != false

    companion object {
        val UNKNOWN = ModelCompatibility(null, null, NpuVerdict.UNKNOWN, null)
    }
}

const val NPU_MIN_WEIGHT_SHARE = 0.8

private val QUANT_SUFFIX = Regex("_(?:XXS|XS|XL|S|M|L)$")
private const val MXFP4_MARKER = "mxfp4"

fun npuWeightShare(weightTypes: Map<String, Long>, capabilities: EngineCapabilities): Double? {
    val total = weightTypes.values.sum()
    if (total <= 0L) return null
    val supported = weightTypes.filterKeys(capabilities::npuSupportsType).values.sum()
    return supported.toDouble() / total
}

fun assessModel(
    meta: GgufMetadata?,
    capabilities: EngineCapabilities,
    npuAvailable: Boolean,
): ModelCompatibility {
    if (meta == null) {
        return ModelCompatibility.UNKNOWN.copy(npu = if (npuAvailable) NpuVerdict.UNKNOWN else NpuVerdict.NO_NPU)
    }
    val share = npuWeightShare(meta.weightTypes, capabilities)
    val npu = when {
        !npuAvailable -> NpuVerdict.NO_NPU
        capabilities.npuWeightTypes.isEmpty() || share == null -> NpuVerdict.UNKNOWN
        share >= NPU_MIN_WEIGHT_SHARE -> NpuVerdict.SUPPORTED
        else -> NpuVerdict.UNSUPPORTED_WEIGHTS
    }
    return ModelCompatibility(
        architecture = meta.architecture,
        architectureSupported = capabilities.supportsArchitecture(meta.architecture),
        npu = npu,
        npuWeightShare = share,
    )
}

fun resolveAccelerator(requested: Accelerator, compatibility: ModelCompatibility): Accelerator =
    when {
        requested != Accelerator.NPU -> requested
        compatibility.npu == NpuVerdict.NO_NPU || compatibility.npu == NpuVerdict.UNSUPPORTED_WEIGHTS -> Accelerator.CPU
        else -> requested
    }

fun weightTypeForQuant(quant: String?, fileName: String = ""): String? {
    if (fileName.contains(MXFP4_MARKER, ignoreCase = true)) return "MXFP4"
    val value = quant?.uppercase() ?: return null
    if (value.startsWith("IQ")) return value
    return value.replace(QUANT_SUFFIX, "")
}

fun npuSupportsQuant(quant: String?, fileName: String, capabilities: EngineCapabilities): Boolean {
    val type = weightTypeForQuant(quant, fileName) ?: return false
    return capabilities.npuSupportsType(type)
}

fun defaultContextSize(profileContext: Int, meta: GgufMetadata?): Int {
    val trained = meta?.contextLength?.takeIf { it > 0 } ?: return profileContext
    return minOf(profileContext, trained)
}
