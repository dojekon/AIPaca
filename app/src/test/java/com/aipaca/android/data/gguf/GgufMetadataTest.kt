package com.aipaca.android.data.gguf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufMetadataTest {

    @Test
    fun readsQwen3StyleScalars() {
        val bytes = gguf {
            string("general.architecture", "qwen3")
            uint32("qwen3.block_count", 36)
            uint32("qwen3.context_length", 32768)
            uint32("qwen3.embedding_length", 2560)
            uint32("qwen3.feed_forward_length", 9728)
            uint32("qwen3.attention.head_count", 32)
            uint32("qwen3.attention.head_count_kv", 8)
            uint32("qwen3.attention.key_length", 128)
            uint32("qwen3.attention.value_length", 128)
            float32("qwen3.rope.freq_base", 1_000_000f)
            stringArray("tokenizer.ggml.tokens", List(5000) { "tok$it" })
            string("tokenizer.chat_template", "x".repeat(10_000))
        }

        val meta = readGgufMetadata(ByteArrayInputStream(bytes))

        assertEquals("qwen3", meta.architecture)
        assertEquals(36, meta.blockCount)
        assertEquals(32768, meta.contextLength)
        assertEquals(8, meta.headCountKvAt(10))
        assertEquals(128, meta.keyLength)
        assertEquals(listOf(9728), meta.feedForwardLength)
        assertEquals(5000, meta.vocabSize)
        assertNull(meta.slidingWindow)
        assertFalse(meta.isSwaLayer(0))
    }

    @Test
    fun readsGemma4StyleArraysAndSwa() {
        val pattern = List(35) { it % 5 != 4 }
        val bytes = gguf {
            string("general.architecture", "gemma4")
            uint32("gemma4.block_count", 35)
            uint32("gemma4.attention.head_count", 8)
            uint32("gemma4.attention.head_count_kv", 1)
            uint32("gemma4.attention.key_length", 512)
            uint32("gemma4.attention.key_length_swa", 256)
            uint32("gemma4.attention.sliding_window", 512)
            uint32("gemma4.attention.shared_kv_layers", 20)
            boolArray("gemma4.attention.sliding_window_pattern", pattern)
            uint32Array("gemma4.feed_forward_length", List(35) { if (it < 15) 6144 else 12288 })
        }

        val meta = readGgufMetadata(ByteArrayInputStream(bytes))

        assertEquals(20, meta.sharedKvLayers)
        assertEquals(pattern, meta.slidingWindowPattern)
        assertTrue(meta.isSwaLayer(0))
        assertFalse(meta.isSwaLayer(4))
        assertEquals(12288, meta.feedForwardLength.max())
        assertEquals(1, meta.headCountKvAt(3))
    }

    @Test
    fun integerPatternMarksLastLayerOfPeriodAsGlobal() {
        val bytes = gguf {
            string("general.architecture", "gemma3")
            uint32("gemma3.block_count", 12)
            uint32("gemma3.attention.sliding_window", 1024)
            uint32("gemma3.attention.sliding_window_pattern", 6)
        }

        val meta = readGgufMetadata(ByteArrayInputStream(bytes))

        assertTrue(meta.isSwaLayer(0))
        assertFalse(meta.isSwaLayer(5))
        assertTrue(meta.isSwaLayer(6))
    }

    @Test
    fun countsWeightElementsByTypeForMatrices() {
        val bytes = gguf {
            string("general.architecture", "lfm3")
            uint32("lfm3.block_count", 2)
            tensor("token_embd.weight", listOf(64, 10), 14)
            tensor("blk.0.attn_q.weight", listOf(64, 64), 12)
            tensor("blk.1.attn_q.weight", listOf(64, 64), 12)
            tensor("blk.0.attn_norm.weight", listOf(64), 0)
        }

        val meta = readGgufMetadata(ByteArrayInputStream(bytes))

        assertEquals("lfm3", meta.architecture)
        assertEquals(mapOf("Q6_K" to 640L, "Q4_K" to 8192L), meta.weightTypes)
    }

    @Test
    fun unknownTensorTypeKeepsNumericName() {
        val bytes = gguf {
            string("general.architecture", "qwen3")
            uint32("qwen3.block_count", 1)
            tensor("blk.0.ffn_up.weight", listOf(32, 32), 99)
        }

        assertEquals(mapOf("TYPE_99" to 1024L), readGgufMetadata(ByteArrayInputStream(bytes)).weightTypes)
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedTensorInfo() {
        val bytes = gguf {
            string("general.architecture", "qwen3")
            uint32("qwen3.block_count", 1)
            tensor("blk.0.ffn_up.weight", listOf(32, 32), 2)
        }
        readGgufMetadata(ByteArrayInputStream(bytes.copyOf(bytes.size - 6)))
    }

    @Test(expected = IOException::class)
    fun rejectsNonGguf() {
        readGgufMetadata(ByteArrayInputStream("not a gguf file at all".toByteArray()))
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedHeader() {
        val bytes = gguf { string("general.architecture", "qwen3") }
        readGgufMetadata(ByteArrayInputStream(bytes.copyOf(bytes.size - 3)))
    }
}

internal class GgufBuilder {
    private val body = ByteArrayOutputStream()
    private val tensors = ByteArrayOutputStream()
    private var tensorCount = 0L
    var count = 0L
        private set

    private fun le(size: Int, block: ByteBuffer.() -> Unit) {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.block()
        body.write(buffer.array())
    }

    private fun rawString(value: String) {
        val bytes = value.toByteArray()
        le(8) { putLong(bytes.size.toLong()) }
        body.write(bytes)
    }

    private fun key(name: String, type: Int) {
        count++
        rawString(name)
        le(4) { putInt(type) }
    }

    fun string(name: String, value: String) {
        key(name, 8)
        rawString(value)
    }

    fun uint32(name: String, value: Int) {
        key(name, 4)
        le(4) { putInt(value) }
    }

    fun float32(name: String, value: Float) {
        key(name, 6)
        le(4) { putFloat(value) }
    }

    fun boolArray(name: String, values: List<Boolean>) {
        key(name, 9)
        le(12) { putInt(7); putLong(values.size.toLong()) }
        values.forEach { body.write(if (it) 1 else 0) }
    }

    fun uint32Array(name: String, values: List<Int>) {
        key(name, 9)
        le(12) { putInt(4); putLong(values.size.toLong()) }
        values.forEach { value -> le(4) { putInt(value) } }
    }

    fun stringArray(name: String, values: List<String>) {
        key(name, 9)
        le(12) { putInt(8); putLong(values.size.toLong()) }
        values.forEach(::rawString)
    }

    fun tensor(name: String, dims: List<Long>, type: Int) {
        tensorCount++
        val nameBytes = name.toByteArray()
        val buffer = ByteBuffer.allocate(8 + nameBytes.size + 4 + dims.size * 8 + 4 + 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(nameBytes.size.toLong())
        buffer.put(nameBytes)
        buffer.putInt(dims.size)
        dims.forEach(buffer::putLong)
        buffer.putInt(type)
        buffer.putLong(0)
        tensors.write(buffer.array())
    }

    fun build(): ByteArray {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.put("GGUF".toByteArray())
        header.putInt(3)
        header.putLong(tensorCount)
        header.putLong(count)
        return header.array() + body.toByteArray() + tensors.toByteArray()
    }
}

internal fun gguf(block: GgufBuilder.() -> Unit): ByteArray = GgufBuilder().apply(block).build()
