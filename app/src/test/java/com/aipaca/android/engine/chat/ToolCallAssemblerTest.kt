package com.aipaca.android.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallAssemblerTest {

    @Test
    fun splitArgumentsAreConcatenatedIntoOneCall() {
        val assembler = ToolCallAssembler()
        assembler.add(ChatEvent.ToolCallDelta(0, "call_1", "read_file", """{"pa"""))
        assembler.add(ChatEvent.ToolCallDelta(0, argsChunk = """th":"a."""))
        assembler.add(ChatEvent.ToolCallDelta(0, argsChunk = """txt"}"""))

        val calls = assembler.finish()

        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("read_file", calls[0].name)
        assertEquals("""{"path":"a.txt"}""", calls[0].argumentsJson)
    }

    @Test
    fun parallelCallsAreReturnedSortedByIndex() {
        val assembler = ToolCallAssembler()
        assembler.add(ChatEvent.ToolCallDelta(0, "call_0", "first", """{"a":1}"""))
        assembler.add(ChatEvent.ToolCallDelta(1, "call_1", "second", """{"b":2}"""))

        val calls = assembler.finish()

        assertEquals(2, calls.size)
        assertEquals(listOf(0, 1), calls.map { it.index })
        assertEquals(listOf("first", "second"), calls.map { it.name })
    }

    @Test
    fun unicodeAndEscapedQuotesArePreserved() {
        val assembler = ToolCallAssembler()
        assembler.add(ChatEvent.ToolCallDelta(0, "call_1", "echo", "{\"text\":\"Привет, "))
        assembler.add(ChatEvent.ToolCallDelta(0, argsChunk = "\\\"мир\\\" и 🚀\"}"))

        val calls = assembler.finish()

        assertEquals("{\"text\":\"Привет, \\\"мир\\\" и 🚀\"}", calls[0].argumentsJson)
    }

    @Test
    fun missingIdAndIndexUseDefaults() {
        val assembler = ToolCallAssembler()
        assembler.add(ChatEvent.ToolCallDelta(index = 0, name = "noop", argsChunk = "{}"))

        val calls = assembler.finish()

        assertEquals(0, calls[0].index)
        assertEquals(null, calls[0].id)
        assertEquals("noop", calls[0].name)
    }

    @Test
    fun idAndNameFromFirstChunkAreRetained() {
        val assembler = ToolCallAssembler()
        assembler.add(ChatEvent.ToolCallDelta(0, "call_1", "first_name", "{"))
        assembler.add(ChatEvent.ToolCallDelta(0, "call_2", "second_name", ""))
        assembler.add(ChatEvent.ToolCallDelta(0, argsChunk = "}"))

        val calls = assembler.finish()

        assertEquals("call_1", calls[0].id)
        assertEquals("first_name", calls[0].name)
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun outOfOrderIndicesAreSortedInFinish() {
        val assembler = ToolCallAssembler()
        assembler.add(ChatEvent.ToolCallDelta(2, "call_2", "third", "{}"))
        assembler.add(ChatEvent.ToolCallDelta(0, "call_0", "first", "{}"))
        assembler.add(ChatEvent.ToolCallDelta(1, "call_1", "second", "{}"))

        val calls = assembler.finish()

        assertEquals(listOf(0, 1, 2), calls.map { it.index })
        assertEquals(listOf("first", "second", "third"), calls.map { it.name })
    }

    @Test
    fun emptyArgumentsProduceEmptyJsonString() {
        val assembler = ToolCallAssembler()
        assembler.add(ChatEvent.ToolCallDelta(0, "call_1", "noop"))

        val calls = assembler.finish()

        assertEquals("", calls[0].argumentsJson)
    }

    @Test
    fun finishIsIdempotentAndAddAfterFinishStillWorks() {
        val assembler = ToolCallAssembler()
        assembler.add(ChatEvent.ToolCallDelta(0, "call_1", "noop", "{}"))

        val first = assembler.finish()
        val second = assembler.finish()
        assembler.add(ChatEvent.ToolCallDelta(1, "call_2", "other", "[]"))
        val third = assembler.finish()

        assertEquals(first, second)
        assertEquals(listOf(0), second.map { it.index })
        assertEquals(listOf(0, 1), third.map { it.index })
    }
}
