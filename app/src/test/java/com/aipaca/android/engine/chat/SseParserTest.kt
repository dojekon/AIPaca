package com.aipaca.android.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {

    @Test
    fun deltaContentIsParsed() {
        val event = parseSsePayload("""{"choices":[{"delta":{"content":"Hello"}}]}""")

        assertEquals(ChatEvent.Delta("Hello"), event)
    }

    @Test
    fun reasoningContentIsParsedAsReasoningDelta() {
        val event = parseSsePayload("""{"choices":[{"delta":{"reasoning_content":"Think"}}]}""")

        assertEquals(ChatEvent.Delta("Think", isReasoning = true), event)
    }

    @Test
    fun contentTakesPriorityOverReasoningInSameChunk() {
        val event = parseSsePayload("""{"choices":[{"delta":{"reasoning_content":"a","content":"b"}}]}""")

        assertEquals(ChatEvent.Delta("b"), event)
    }

    @Test
    fun emptyReasoningContentIsIgnored() {
        val event = parseSsePayload("""{"choices":[{"delta":{"reasoning_content":""}}]}""")

        assertEquals(ChatEvent.Ignored, event)
    }

    @Test
    fun firstChunkWithRoleOnlyIsIgnored() {
        val event = parseSsePayload("""{"choices":[{"delta":{"role":"assistant"}}]}""")

        assertEquals(ChatEvent.Ignored, event)
    }

    @Test
    fun emptyDeltaIsIgnored() {
        val event = parseSsePayload("""{"choices":[{"delta":{}}]}""")

        assertEquals(ChatEvent.Ignored, event)
    }

    @Test
    fun doneMarkerReturnsDoneWithoutTimings() {
        val event = parseSsePayload("[DONE]")

        assertEquals(ChatEvent.Done(null), event)
    }

    @Test
    fun timingsChunkReturnsDoneWithExactNumbers() {
        val event = parseSsePayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}],""" +
                """"timings":{"predicted_per_second":12.3,"prompt_per_second":140.1,"predicted_n":128,"prompt_n":512}}""",
        )

        assertEquals(ChatEvent.Done(ChatTimings(12.3, 140.1, 128, 512)), event)
    }

    @Test
    fun timingsWithMissingFieldsReturnsDoneWithNulls() {
        val event = parseSsePayload("""{"timings":{"predicted_n":42}}""")

        assertEquals(ChatEvent.Done(ChatTimings(null, null, 42, null)), event)
    }

    @Test
    fun brokenJsonIsIgnored() {
        val event = parseSsePayload("""{"choices":[{"delta":{"content":"oops"}""")

        assertEquals(ChatEvent.Ignored, event)
    }

    @Test
    fun blankPayloadIsIgnored() {
        assertEquals(ChatEvent.Ignored, parseSsePayload(""))
        assertEquals(ChatEvent.Ignored, parseSsePayload("   "))
    }

    @Test
    fun unicodeAndNewlinesArePreserved() {
        val event = parseSsePayload("""{"choices":[{"delta":{"content":"Привет\nмир 🌍"}}]}""")

        assertEquals(ChatEvent.Delta("Привет\nмир 🌍"), event)
    }

    @Test
    fun unicodeEscapeIsDecoded() {
        val event = parseSsePayload("""{"choices":[{"delta":{"content":"\u0416"}}]}""")

        assertEquals(ChatEvent.Delta("Ж"), event)
    }

    @Test
    fun chunkWithoutKeysIsIgnored() {
        val event = parseSsePayload("""{"id":"abc","object":"chat.completion.chunk"}""")

        assertEquals(ChatEvent.Ignored, event)
    }

    @Test
    fun nullContentIsIgnored() {
        val event = parseSsePayload("""{"choices":[{"delta":{"content":null}}]}""")

        assertEquals(ChatEvent.Ignored, event)
    }

    @Test
    fun emptyContentIsIgnored() {
        val event = parseSsePayload("""{"choices":[{"delta":{"content":""}}]}""")

        assertEquals(ChatEvent.Ignored, event)
    }

    @Test
    fun usageOnlyChunkIsIgnored() {
        val event = parseSsePayload("""{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10}}""")

        assertEquals(ChatEvent.Ignored, event)
    }

    @Test
    fun nonObjectJsonIsIgnored() {
        assertEquals(ChatEvent.Ignored, parseSsePayload("[]"))
        assertEquals(ChatEvent.Ignored, parseSsePayload("42"))
    }
}
