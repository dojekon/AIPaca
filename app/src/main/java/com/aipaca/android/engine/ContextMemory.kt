package com.aipaca.android.engine

import com.aipaca.android.data.gguf.GgufMetadata
import com.aipaca.android.engine.profiles.ProfileDoc

const val SERVER_PARALLEL_SLOTS = 4
const val KV_CELL_PADDING = 256
const val NPU_SESSION_VMEM_BYTES = 3_355_443_200L

private const val COMPUTE_BASE_BYTES = 320L * 1024 * 1024
private const val ACTIVATION_FACTOR = 4
private const val FLOAT_BYTES = 4L

enum class MemoryFit { FITS, TIGHT, TOO_BIG }

data class ContextMemoryEstimate(
    val nCtx: Int,
    val weightsBytes: Long,
    val kvBytes: Long,
    val computeBytes: Long,
) {
    val totalBytes: Long get() = weightsBytes + kvBytes + computeBytes
}

fun kvBytesPerElement(type: String?): Double = when (type?.lowercase()) {
    null, "f16", "bf16" -> 2.0
    "f32" -> 4.0
    "q8_0" -> 34.0 / 32.0
    "q5_0" -> 22.0 / 32.0
    "q5_1" -> 24.0 / 32.0
    "q4_0" -> 18.0 / 32.0
    "q4_1" -> 20.0 / 32.0
    "iq4_nl" -> 18.0 / 32.0
    else -> 2.0
}

fun padTo(value: Int, multiple: Int): Int =
    if (multiple <= 0) value else ((value + multiple - 1) / multiple) * multiple

fun kvCacheBytes(
    meta: GgufMetadata,
    nCtx: Int,
    kvTypeK: String?,
    kvTypeV: String?,
    ubatch: Int,
    parallelSlots: Int = SERVER_PARALLEL_SLOTS,
): Long {
    val baseCells = padTo(nCtx.coerceAtLeast(1), KV_CELL_PADDING)
    val swaCells = meta.slidingWindow?.takeIf { it > 0 }?.let { window ->
        padTo(minOf(baseCells, window * parallelSlots + ubatch), KV_CELL_PADDING)
    } ?: baseCells
    val bytesK = kvBytesPerElement(kvTypeK)
    val bytesV = kvBytesPerElement(kvTypeV)
    val kvLayers = (meta.blockCount - meta.sharedKvLayers).coerceIn(0, meta.blockCount)
    val fallbackHead = meta.embeddingLength?.let { embedding ->
        meta.headCountAt(0)?.takeIf { it > 0 }?.let { embedding / it }
    }
    var total = 0.0
    for (layer in 0 until kvLayers) {
        val swa = meta.isSwaLayer(layer)
        val headsKv = meta.headCountKvAt(layer) ?: continue
        val keyDim = (if (swa) meta.keyLengthSwa ?: meta.keyLength else meta.keyLength) ?: fallbackHead ?: continue
        val valueDim = (if (swa) meta.valueLengthSwa ?: meta.valueLength else meta.valueLength) ?: keyDim
        val cells = if (swa) swaCells else baseCells
        total += cells.toDouble() * headsKv * (keyDim * bytesK + valueDim * bytesV)
    }
    return total.toLong()
}

fun computeBufferBytes(meta: GgufMetadata, ubatch: Int): Long {
    val vocab = (meta.vocabSize ?: 0).toLong()
    val hidden = maxOf(
        meta.feedForwardLength.maxOrNull() ?: 0,
        (meta.embeddingLength ?: 0) * ACTIVATION_FACTOR,
    ).toLong()
    return COMPUTE_BASE_BYTES + ubatch.toLong() * FLOAT_BYTES * (vocab + ACTIVATION_FACTOR * hidden)
}

fun estimateContextMemory(
    meta: GgufMetadata,
    weightsBytes: Long,
    nCtx: Int,
    kvTypeK: String?,
    kvTypeV: String?,
    ubatch: Int,
): ContextMemoryEstimate = ContextMemoryEstimate(
    nCtx = nCtx,
    weightsBytes = weightsBytes,
    kvBytes = kvCacheBytes(meta, nCtx, kvTypeK, kvTypeV, ubatch),
    computeBytes = computeBufferBytes(meta, ubatch),
)

fun memoryFit(totalBytes: Long, availableBytes: Long, comfortRatio: Double = 0.8): MemoryFit = when {
    availableBytes <= 0L -> MemoryFit.TOO_BIG
    totalBytes <= (availableBytes * comfortRatio).toLong() -> MemoryFit.FITS
    totalBytes <= availableBytes -> MemoryFit.TIGHT
    else -> MemoryFit.TOO_BIG
}

fun exceedsNpuSession(estimate: ContextMemoryEstimate): Boolean =
    estimate.weightsBytes + estimate.kvBytes > NPU_SESSION_VMEM_BYTES

fun contextSliderMax(meta: GgufMetadata?, cap: Int = CONTEXT_SLIDER_CAP): Int =
    (meta?.contextLength ?: cap).coerceIn(CONTEXT_SLIDER_MIN, cap)

fun snapContext(value: Float): Int {
    val steps = Math.round(value / CONTEXT_SLIDER_STEP)
    return (steps * CONTEXT_SLIDER_STEP).coerceAtLeast(CONTEXT_SLIDER_MIN)
}

const val CONTEXT_SLIDER_MIN = 512
const val CONTEXT_SLIDER_STEP = 512
const val CONTEXT_SLIDER_CAP = 32768

fun ProfileDoc.withContextSize(nCtx: Int?): ProfileDoc =
    if (nCtx == null || nCtx <= 0) this else copy(launch = launch.copy(nCtx = nCtx))

private val MEMORY_LOG_REGEX =
    Regex("""buffer size|kv.cache|memory_breakdown|n_ctx|graph splits|n_parallel""", RegexOption.IGNORE_CASE)

fun isMemoryLogLine(line: String): Boolean = MEMORY_LOG_REGEX.containsMatchIn(line)
