package com.aipaca.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTitleTest {

    @Test
    fun plainTitleIsKept() {
        assertEquals("Тема диалога", sanitizeGeneratedTitle("Тема диалога"))
    }

    @Test
    fun boldMarkdownIsStripped() {
        assertEquals("Тема диалога", sanitizeGeneratedTitle("**Тема диалога**"))
    }

    @Test
    fun headingAndBackticksAreStripped() {
        assertEquals("Тема чата", sanitizeGeneratedTitle("## `Тема чата`"))
    }

    @Test
    fun bracketsAndTildesAreStripped() {
        assertEquals("Тема чата", sanitizeGeneratedTitle("~~[Тема чата]~~"))
    }

    @Test
    fun quotesAreStripped() {
        assertEquals("Тема чата", sanitizeGeneratedTitle("«Тема чата»"))
        assertEquals("Тема чата", sanitizeGeneratedTitle("\"Тема чата\""))
        assertEquals("Тема чата", sanitizeGeneratedTitle("'Тема чата'"))
    }

    @Test
    fun curlyQuotesAreStripped() {
        assertEquals("Тема чата", sanitizeGeneratedTitle("“Тема чата”"))
    }

    @Test
    fun multiLineResponseUsesFirstNonEmptyLine() {
        assertEquals("Тема чата", sanitizeGeneratedTitle("\n\nТема чата\nВот такой заголовок"))
    }

    @Test
    fun fencedCodeBlockReturnsNull() {
        assertNull(sanitizeGeneratedTitle("```\nТема чата\n```"))
        assertNull(sanitizeGeneratedTitle("```bash\nТема\n```"))
    }

    @Test
    fun zagolovokPrefixIsStripped() {
        assertEquals("Тема чата", sanitizeGeneratedTitle("Заголовок: Тема чата"))
    }

    @Test
    fun titlePrefixIsStrippedCaseInsensitively() {
        assertEquals("Chat topic", sanitizeGeneratedTitle("TITLE: Chat topic"))
        assertEquals("Chat topic", sanitizeGeneratedTitle("title: Chat topic"))
    }

    @Test
    fun prefixInsideQuotesIsStripped() {
        assertEquals("Тема чата", sanitizeGeneratedTitle("**Заголовок:** «Тема чата»"))
    }

    @Test
    fun nullOrBlankReturnsNull() {
        assertNull(sanitizeGeneratedTitle(null))
        assertNull(sanitizeGeneratedTitle(""))
        assertNull(sanitizeGeneratedTitle("   \n\t "))
    }

    @Test
    fun symbolsOnlyReturnsNull() {
        assertNull(sanitizeGeneratedTitle("..."))
        assertNull(sanitizeGeneratedTitle("***"))
        assertNull(sanitizeGeneratedTitle("##"))
    }

    @Test
    fun emojiOnlyReturnsNull() {
        assertNull(sanitizeGeneratedTitle("🔥"))
        assertNull(sanitizeGeneratedTitle("🙂🙂"))
    }

    @Test
    fun titleIsTruncatedToFortyChars() {
        assertEquals("a".repeat(40), sanitizeGeneratedTitle("a".repeat(60)))
    }

    @Test
    fun truncationDoesNotLeaveTrailingSpace() {
        assertEquals("a".repeat(39), sanitizeGeneratedTitle("a".repeat(39) + " b"))
    }

    @Test
    fun innerWhitespaceIsCollapsed() {
        assertEquals("Тема чата", sanitizeGeneratedTitle("Тема    чата"))
    }

    @Test
    fun titleIsGeneratedForSecondMessage() {
        assertTrue(shouldGenerateTitle(2, "Привет, как дела?", "Привет, как дела?"))
    }

    @Test
    fun manualTitleIsNotOverwritten() {
        assertFalse(shouldGenerateTitle(2, "Моё имя", "Привет, как дела?"))
    }

    @Test
    fun laterExchangeDoesNotRegenerate() {
        assertFalse(shouldGenerateTitle(4, "Тема", "Тема"))
    }

    @Test
    fun firstMessageDoesNotGenerate() {
        assertFalse(shouldGenerateTitle(1, "Тема", "Тема"))
    }

    @Test
    fun titleRequestUsesLowTemperatureAndTokenLimit() {
        val request = buildTitleRequest("Привет", "Здравствуйте! Чем помочь?")

        assertEquals(0.2, request.temperature, 0.0)
        assertEquals(24, request.maxTokens)
        assertEquals("user", request.messages.single().role)
        assertTrue(request.systemPrompt.orEmpty().contains("заголов"))
    }

    @Test
    fun titleRequestIncludesBothSidesOfTheExchange() {
        val content = buildTitleRequest("Привет", "Здравствуйте").messages.single().content

        assertTrue(content.contains("Привет"))
        assertTrue(content.contains("Здравствуйте"))
    }

    @Test
    fun longMessagesAreTruncatedTo500CharsEach() {
        val user = "u".repeat(600)
        val assistant = "a".repeat(700)

        val content = buildTitleRequest(user, assistant).messages.single().content

        assertEquals("u".repeat(500) + "\n" + "a".repeat(500), content)
    }

    @Test
    fun titleRequestDisablesModelThinking() {
        val request = buildTitleRequest("Привет", "Здравствуйте")

        assertEquals(false, request.enableThinking)
    }
}
