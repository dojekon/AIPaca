package com.aipaca.android.data.hf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSupportTest {

    @Test
    fun toolUseTagIsDetectedWithCaseVariants() {
        assertEquals(true, detectTools(listOf("tool-use"), emptyList(), null))
        assertEquals(true, detectTools(listOf("Tool-Use"), emptyList(), null))
        assertEquals(true, detectTools(listOf("TOOL_USE"), emptyList(), null))
    }

    @Test
    fun functionCallingTagIsDetectedWithCaseVariants() {
        assertEquals(true, detectTools(listOf("function-calling"), emptyList(), null))
        assertEquals(true, detectTools(listOf("Function-Calling"), emptyList(), null))
        assertEquals(true, detectTools(listOf("FUNCTION_CALLING"), emptyList(), null))
    }

    @Test
    fun tagContainingMarkerIsDetected() {
        assertEquals(true, detectTools(listOf("  chat-tool-use-v2  "), emptyList(), null))
    }

    @Test
    fun quotedToolsVariableIsDetected() {
        val template = "{% if \"tools\" in message %}...{% endif %}"
        assertEquals(true, detectTools(emptyList(), emptyList(), template))
        assertTrue(containsToolMarkers(template))
    }

    @Test
    fun toolCallsUnicodeMarkerIsDetected() {
        val template = "text <|tool\u2581calls|> suffix"
        assertEquals(true, detectTools(emptyList(), emptyList(), template))
        assertTrue(containsToolMarkers(template))
    }

    @Test
    fun toolCallStartMarkerIsDetected() {
        val template = "text tool_call_start suffix"
        assertEquals(true, detectTools(emptyList(), emptyList(), template))
        assertTrue(containsToolMarkers(template))
    }

    @Test
    fun templateWithoutMarkersIsFalse() {
        val template = "{% for message in messages %}{{ message.content }}{% endfor %}"
        assertEquals(false, detectTools(emptyList(), emptyList(), template))
        assertFalse(containsToolMarkers(template))
    }

    @Test
    fun noTagNoTemplateIsNullEvenWithTokenizerConfig() {
        assertNull(detectTools(emptyList(), listOf("tokenizer_config.json"), null))
        assertNull(detectTools(emptyList(), listOf("nested/tokenizer_config.json"), null))
        assertNull(detectTools(emptyList(), listOf("chat_template.jinja"), null))
    }

    @Test
    fun chatTemplateFileIsDetected() {
        assertTrue(hasChatTemplateFile(listOf("tokenizer_config.json")))
        assertTrue(hasChatTemplateFile(listOf("nested/dir/tokenizer_config.json")))
        assertTrue(hasChatTemplateFile(listOf("chat_template.jinja")))
        assertTrue(hasChatTemplateFile(listOf("nested/dir/chat_template.jinja")))
        assertFalse(hasChatTemplateFile(listOf("config.json", "model.gguf")))
        assertFalse(hasChatTemplateFile(emptyList()))
    }

    @Test
    fun extractStringChatTemplate() {
        val json = """{"chat_template": "Hello {{ messages }}"}"""
        assertEquals("Hello {{ messages }}", extractChatTemplate(json))
    }

    @Test
    fun extractArrayOfStringsChatTemplate() {
        val json = """{"chat_template": ["first", "second"]}"""
        assertEquals("first\nsecond", extractChatTemplate(json))
    }

    @Test
    fun extractArrayOfObjectsChatTemplate() {
        val json = """{"chat_template": [{"name": "default", "template": "first"}, {"template": "second"}]}"""
        assertEquals("first\nsecond", extractChatTemplate(json))
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertNull(extractChatTemplate("not json"))
        assertNull(extractChatTemplate("{invalid"))
        assertNull(extractChatTemplate("[]"))
    }

    @Test
    fun blankOrMissingChatTemplateReturnsNull() {
        assertNull(extractChatTemplate(null))
        assertNull(extractChatTemplate(""))
        assertNull(extractChatTemplate("   "))
        assertNull(extractChatTemplate("{}"))
        assertNull(extractChatTemplate("""{"chat_template": ""}"""))
        assertNull(extractChatTemplate("""{"chat_template": "   "}"""))
        assertNull(extractChatTemplate("""{"chat_template": []}"""))
        assertNull(extractChatTemplate("""{"chat_template": 42}"""))
    }

    @Test
    fun unicodeAndEscapedTemplateTextIsPreserved() {
        val json = """{"chat_template": "a <|tool\u2581calls|> b\nc \u00e9"}"""
        assertEquals("a <|tool\u2581calls|> b\nc \u00e9", extractChatTemplate(json))
    }

    @Test
    fun toolsSupportedValueMapsDetectedBooleans() {
        assertEquals(1, toolsSupportedValue(true))
        assertEquals(0, toolsSupportedValue(false))
    }

    @Test
    fun toolsSupportedValueKeepsUnknownAsNull() {
        assertNull(toolsSupportedValue(null))
    }

    @Test
    fun extractedTemplateFeedsDetection() {
        val json = """{"chat_template": "{% if \"tools\" %}...{% endif %}"}"""
        assertEquals(true, detectTools(emptyList(), emptyList(), extractChatTemplate(json)))
    }
}
