package com.aipaca.android.data.hf

import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val GGUF_SUFFIX = ".gguf"
private const val GIB = 1024L * 1024L * 1024L
private const val ENOUGH_LIMIT = 5L * GIB
private const val MAYBE_LIMIT = 9L * GIB

private val QUANT_PATTERN = Regex(
    "(?<![0-9A-Za-z])(?:IQ|Q)\\d+(?:_[0-9A-Z]+)*(?![0-9A-Za-z])",
    RegexOption.IGNORE_CASE,
)

@Serializable
data class HfModelSummary(
    val id: String,
    val downloads: Long? = null,
    val likes: Long? = null,
    @Serializable(with = HfGatedSerializer::class) val gated: Boolean? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class HfModelDetails(
    val id: String,
    @Serializable(with = HfGatedSerializer::class) val gated: Boolean? = null,
    val siblings: List<HfSibling> = emptyList(),
    val blobs: List<HfBlob> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class HfGgufSummary(
    val architecture: String? = null,
)

@Serializable
data class HfModelGguf(
    val gguf: HfGgufSummary? = null,
)

@Serializable
data class HfSibling(
    val rfilename: String,
    val size: Long? = null,
    val lfs: HfLfs? = null,
)

@Serializable
data class HfBlob(
    val rfilename: String,
    val size: Long? = null,
    val lfs: HfLfs? = null,
)

@Serializable
data class HfLfs(
    val oid: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
)

data class HfGgufFile(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val downloadUrl: String,
)

enum class RamFit { ENOUGH, MAYBE, TOO_BIG }

fun HfModelDetails.ggufFiles(repoId: String): List<HfGgufFile> {
    val blobsByName = blobs.associateBy { it.rfilename }
    return siblings
        .filter { it.rfilename.endsWith(GGUF_SUFFIX, ignoreCase = true) }
        .map { sibling ->
            val blob = blobsByName[sibling.rfilename]
            HfGgufFile(
                fileName = sibling.rfilename,
                sizeBytes = sibling.lfs?.size ?: sibling.size ?: blob?.lfs?.size ?: blob?.size ?: 0L,
                sha256 = sibling.lfs?.sha256 ?: sibling.lfs?.oid
                    ?: blob?.lfs?.sha256 ?: blob?.lfs?.oid,
                downloadUrl = resolveUrl(repoId, sibling.rfilename),
            )
        }
        .sortedBy { it.fileName }
}

fun resolveUrl(repoId: String, fileName: String, revision: String = "main"): String =
    "https://huggingface.co/$repoId/resolve/$revision/$fileName"

fun quantFromFileName(fileName: String): String? =
    QUANT_PATTERN.findAll(fileName).lastOrNull()?.value?.uppercase(Locale.US)

fun ramFitFor(sizeBytes: Long): RamFit = when {
    sizeBytes < ENOUGH_LIMIT -> RamFit.ENOUGH
    sizeBytes < MAYBE_LIMIT -> RamFit.MAYBE
    else -> RamFit.TOO_BIG
}

private object HfGatedSerializer : KSerializer<Boolean?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.aipaca.android.data.hf.HfGated", PrimitiveKind.BOOLEAN).nullable

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeBoolean(value)
        }
    }

    override fun deserialize(decoder: Decoder): Boolean? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> element.booleanOrNull ?: (element.contentOrNull != null)
            else -> null
        }
    }
}
