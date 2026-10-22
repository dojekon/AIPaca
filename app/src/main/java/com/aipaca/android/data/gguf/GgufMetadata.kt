package com.aipaca.android.data.gguf

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

data class GgufMetadata(
    val architecture: String,
    val contextLength: Int?,
    val blockCount: Int,
    val embeddingLength: Int?,
    val headCount: List<Int>,
    val headCountKv: List<Int>,
    val keyLength: Int?,
    val valueLength: Int?,
    val keyLengthSwa: Int?,
    val valueLengthSwa: Int?,
    val slidingWindow: Int?,
    val slidingWindowPattern: List<Boolean>?,
    val slidingWindowPeriod: Int?,
    val sharedKvLayers: Int,
    val feedForwardLength: List<Int>,
    val vocabSize: Int?,
) {
    fun headCountAt(layer: Int): Int? = headCount.valueAt(layer)

    fun headCountKvAt(layer: Int): Int? = headCountKv.valueAt(layer) ?: headCountAt(layer)

    fun isSwaLayer(layer: Int): Boolean {
        val window = slidingWindow ?: return false
        if (window <= 0) return false
        slidingWindowPattern?.let { return it.getOrElse(layer) { false } }
        val period = slidingWindowPeriod ?: return true
        if (period <= 0) return true
        return layer % period < period - 1
    }

    private fun List<Int>.valueAt(layer: Int): Int? = when {
        isEmpty() -> null
        size == 1 -> first()
        else -> getOrNull(layer)
    }
}

private const val GGUF_MAGIC = 0x46554747
private const val MAX_STRING_BYTES = 64L * 1024 * 1024
private const val MAX_KEPT_ARRAY = 4096

private const val TYPE_UINT8 = 0
private const val TYPE_INT8 = 1
private const val TYPE_UINT16 = 2
private const val TYPE_INT16 = 3
private const val TYPE_UINT32 = 4
private const val TYPE_INT32 = 5
private const val TYPE_FLOAT32 = 6
private const val TYPE_BOOL = 7
private const val TYPE_STRING = 8
private const val TYPE_ARRAY = 9
private const val TYPE_UINT64 = 10
private const val TYPE_INT64 = 11
private const val TYPE_FLOAT64 = 12

fun readGgufMetadata(file: File): GgufMetadata =
    BufferedInputStream(file.inputStream(), 64 * 1024).use { readGgufMetadata(it) }

fun readGgufMetadata(input: InputStream): GgufMetadata {
    val reader = LittleEndianReader(input)
    if (reader.int32() != GGUF_MAGIC) throw IOException("Not a GGUF file")
    val version = reader.int32()
    if (version < 2) throw IOException("Unsupported GGUF version $version")
    reader.int64()
    val kvCount = reader.int64()
    val values = HashMap<String, Any>()
    for (index in 0 until kvCount) {
        val key = reader.string()
        val type = reader.int32()
        val value = reader.value(type)
        if (value != null) values[key] = value
    }
    return buildMetadata(values)
}

private fun buildMetadata(values: Map<String, Any>): GgufMetadata {
    val arch = values["general.architecture"] as? String ?: throw IOException("GGUF has no architecture")
    fun key(name: String) = "$arch.$name"
    fun int(name: String): Int? = (values[key(name)] as? Number)?.toInt()
    fun ints(name: String): List<Int> = when (val value = values[key(name)]) {
        is Number -> listOf(value.toInt())
        is List<*> -> value.mapNotNull { (it as? Number)?.toInt() }
        else -> emptyList()
    }
    val pattern = values[key("attention.sliding_window_pattern")]
    return GgufMetadata(
        architecture = arch,
        contextLength = int("context_length"),
        blockCount = int("block_count") ?: throw IOException("GGUF has no block_count"),
        embeddingLength = int("embedding_length"),
        headCount = ints("attention.head_count"),
        headCountKv = ints("attention.head_count_kv"),
        keyLength = int("attention.key_length"),
        valueLength = int("attention.value_length"),
        keyLengthSwa = int("attention.key_length_swa"),
        valueLengthSwa = int("attention.value_length_swa"),
        slidingWindow = int("attention.sliding_window"),
        slidingWindowPattern = (pattern as? List<*>)?.map { it == true },
        slidingWindowPeriod = (pattern as? Number)?.toInt(),
        sharedKvLayers = int("attention.shared_kv_layers") ?: 0,
        feedForwardLength = ints("feed_forward_length"),
        vocabSize = int("vocab_size") ?: (values["tokenizer.ggml.tokens"] as? ArrayLength)?.count?.toInt(),
    )
}

private data class ArrayLength(val count: Long)

private class LittleEndianReader(private val input: InputStream) {

    private val buffer = ByteArray(8)

    fun value(type: Int): Any? = when (type) {
        TYPE_UINT8 -> byte().toInt() and 0xFF
        TYPE_INT8 -> byte().toInt()
        TYPE_UINT16 -> int16() and 0xFFFF
        TYPE_INT16 -> int16().toShort().toInt()
        TYPE_UINT32 -> int32().toLong() and 0xFFFFFFFFL
        TYPE_INT32 -> int32()
        TYPE_FLOAT32 -> Float.fromBits(int32())
        TYPE_BOOL -> byte().toInt() != 0
        TYPE_STRING -> string()
        TYPE_ARRAY -> array()
        TYPE_UINT64, TYPE_INT64 -> int64()
        TYPE_FLOAT64 -> Double.fromBits(int64())
        else -> throw IOException("Unknown GGUF value type $type")
    }

    private fun array(): Any {
        val elementType = int32()
        val count = int64()
        if (count < 0) throw IOException("Negative GGUF array length")
        if (count > MAX_KEPT_ARRAY || elementType == TYPE_STRING || elementType == TYPE_ARRAY) {
            repeat(count) { skipValue(elementType) }
            return ArrayLength(count)
        }
        return List(count.toInt()) { value(elementType) }
    }

    private fun skipValue(type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skip(1)
            TYPE_UINT16, TYPE_INT16 -> skip(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skip(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skip(8)
            TYPE_STRING -> skip(stringLength())
            TYPE_ARRAY -> {
                val elementType = int32()
                repeat(int64()) { skipValue(elementType) }
            }
            else -> throw IOException("Unknown GGUF value type $type")
        }
    }

    fun string(): String {
        val length = stringLength()
        val bytes = ByteArray(length.toInt())
        readFully(bytes, bytes.size)
        return String(bytes, Charsets.UTF_8)
    }

    private fun stringLength(): Long {
        val length = int64()
        if (length < 0 || length > MAX_STRING_BYTES) throw IOException("Bad GGUF string length $length")
        return length
    }

    private fun byte(): Byte {
        readFully(buffer, 1)
        return buffer[0]
    }

    private fun int16(): Int {
        readFully(buffer, 2)
        return (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
    }

    fun int32(): Int {
        readFully(buffer, 4)
        var result = 0
        for (i in 3 downTo 0) result = (result shl 8) or (buffer[i].toInt() and 0xFF)
        return result
    }

    fun int64(): Long {
        readFully(buffer, 8)
        var result = 0L
        for (i in 7 downTo 0) result = (result shl 8) or (buffer[i].toLong() and 0xFF)
        return result
    }

    private fun readFully(target: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(target, offset, length - offset)
            if (read < 0) throw EOFException("Unexpected end of GGUF header")
            offset += read
        }
    }

    private fun skip(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw EOFException("Unexpected end of GGUF header")
                remaining--
            }
        }
    }

    private inline fun repeat(times: Long, action: () -> Unit) {
        var index = 0L
        while (index < times) {
            action()
            index++
        }
    }
}
