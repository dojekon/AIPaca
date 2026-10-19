package com.aipaca.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelTest {

    @Test
    fun shortTextIsKeptAsIs() {
        assertEquals("Hello world", chatTitleFrom("Hello world"))
    }

    @Test
    fun textLongerThanFortyCharsIsTruncated() {
        assertEquals("a".repeat(40), chatTitleFrom("a".repeat(50)))
    }

    @Test
    fun fortyCharsAreNotTruncated() {
        val text = "b".repeat(40)

        assertEquals(text, chatTitleFrom(text))
    }

    @Test
    fun newlinesBecomeSpaces() {
        assertEquals("First line second line", chatTitleFrom("First line\nsecond line"))
    }

    @Test
    fun carriageReturnsBecomeSpaces() {
        assertEquals("one two", chatTitleFrom("one\r\ntwo"))
    }

    @Test
    fun emptyTextStaysEmpty() {
        assertEquals("", chatTitleFrom(""))
    }

    @Test
    fun blankTextIsTrimmed() {
        assertEquals("", chatTitleFrom("  \n\t "))
    }
}
