package com.aipaca.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownModelTest {

    @Test
    fun parsesHeadingLevelsOneToSix() {
        val blocks = parseMarkdown("# a\n## b\n### c\n#### d\n##### e\n###### f")

        assertEquals(6, blocks.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), blocks.map { (it as MdBlock.Heading).level })
        assertEquals(listOf(MdSpan.Text("a")), (blocks[0] as MdBlock.Heading).spans)
        assertEquals(listOf(MdSpan.Text("f")), (blocks[5] as MdBlock.Heading).spans)
    }

    @Test
    fun stripsClosingHashesFromHeading() {
        val heading = parseMarkdown("## Title ##").single() as MdBlock.Heading

        assertEquals(listOf(MdSpan.Text("Title")), heading.spans)
    }

    @Test
    fun hashWithoutSpaceIsParagraph() {
        val lines = singleParagraph("#notext")

        assertEquals(listOf(MdSpan.Text("#notext")), lines[0])
    }

    @Test
    fun paragraphKeepsSoftBreaks() {
        val blocks = parseMarkdown("line one\nline two\n\nsecond")

        assertEquals(2, blocks.size)
        val first = blocks[0] as MdBlock.Paragraph
        assertEquals(2, first.lines.size)
        assertEquals(listOf(MdSpan.Text("line one")), first.lines[0])
        assertEquals(listOf(MdSpan.Text("line two")), first.lines[1])
    }

    @Test
    fun parsesBulletListItems() {
        val blocks = parseMarkdown("- one\n* two\n+ three")

        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.BulletList
        assertEquals(3, list.items.size)
        assertEquals(listOf("•", "•", "•"), list.items.map { it.marker })
        assertEquals(listOf(listOf(MdSpan.Text("two"))), list.items[1].lines)
    }

    @Test
    fun parsesOrderedListAndPreservesNumbers() {
        val blocks = parseMarkdown("1. first\n2) second\n10. third")

        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.OrderedList
        assertEquals(listOf("1.", "2)", "10."), list.items.map { it.marker })
        assertEquals(listOf(listOf(MdSpan.Text("second"))), list.items[1].lines)
    }

    @Test
    fun blankLineEndsList() {
        val blocks = parseMarkdown("- item\n\nafter")

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.BulletList)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test
    fun switchingMarkerKindStartsNewList() {
        val blocks = parseMarkdown("- one\n1. two")

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.BulletList)
        assertTrue(blocks[1] is MdBlock.OrderedList)
    }

    @Test
    fun listItemKeepsContinuationLines() {
        val blocks = parseMarkdown("- first\n  continued\n- second")

        val list = blocks.single() as MdBlock.BulletList
        assertEquals(2, list.items.size)
        assertEquals(2, list.items[0].lines.size)
        assertEquals(listOf(MdSpan.Text("continued")), list.items[0].lines[1])
    }

    @Test
    fun parsesNestedBoldItalic() {
        val spans = singleParagraph("**bold *italic* end**")[0]

        val bold = spans.single() as MdSpan.Bold
        assertEquals(3, bold.spans.size)
        assertEquals(MdSpan.Text("bold "), bold.spans[0])
        assertEquals(listOf(MdSpan.Text("italic")), (bold.spans[1] as MdSpan.Italic).spans)
        assertEquals(MdSpan.Text(" end"), bold.spans[2])
    }

    @Test
    fun parsesTripleAsteriskAsBoldItalic() {
        val spans = singleParagraph("***both***")[0]

        val bold = spans.single() as MdSpan.Bold
        val italic = bold.spans.single() as MdSpan.Italic
        assertEquals(listOf(MdSpan.Text("both")), italic.spans)
    }

    @Test
    fun parsesUnderscoreBoldAndItalic() {
        val spans = singleParagraph("__bold__ and _italic_")[0]

        assertEquals(MdSpan.Bold(listOf(MdSpan.Text("bold"))), spans[0])
        assertEquals(MdSpan.Italic(listOf(MdSpan.Text("italic"))), spans[2])
    }

    @Test
    fun inlineCodeDoesNotParseMarkup() {
        val spans = singleParagraph("`a *b* **c**`")[0]

        assertEquals(listOf(MdSpan.Code("a *b* **c**")), spans)
    }

    @Test
    fun inlineCodeWithDoubleBacktick() {
        val spans = singleParagraph("``a `b` c``")[0]

        assertEquals(listOf(MdSpan.Code("a `b` c")), spans)
    }

    @Test
    fun parsesStrikethrough() {
        val spans = singleParagraph("~~gone~~")[0]

        assertEquals(listOf(MdSpan.Strikethrough(listOf(MdSpan.Text("gone")))), spans)
    }

    @Test
    fun unclosedEmphasisIsLiteralText() {
        val spans = singleParagraph("**bold and *italic")[0]

        assertEquals(listOf(MdSpan.Text("**bold and *italic")), spans)
    }

    @Test
    fun intrawordUnderscoresAreLiteral() {
        val spans = singleParagraph("file_name_here")[0]

        assertEquals(listOf(MdSpan.Text("file_name_here")), spans)
    }

    @Test
    fun parsesLink() {
        val spans = singleParagraph("see [**site**](https://example.com/x)")[0]

        assertEquals(MdSpan.Text("see "), spans[0])
        val link = spans[1] as MdSpan.Link
        assertEquals("https://example.com/x", link.url)
        assertEquals(listOf(MdSpan.Bold(listOf(MdSpan.Text("site")))), link.spans)
    }

    @Test
    fun malformedLinkStaysLiteral() {
        val spans = singleParagraph("[label] (url)")[0]

        assertEquals(listOf(MdSpan.Text("[label] (url)")), spans)
    }

    @Test
    fun backslashEscapesMarkers() {
        val spans = singleParagraph("\\*not italic\\* and \\`code\\`")[0]

        assertEquals(listOf(MdSpan.Text("*not italic* and `code`")), spans)
    }

    @Test
    fun fencedCodeWithLanguage() {
        val blocks = parseMarkdown("```kotlin\nval x = 1\nprintln(x)\n```")

        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
    }

    @Test
    fun fencedCodeWithoutLanguage() {
        val blocks = parseMarkdown("```\nplain\n```")

        val code = blocks.single() as MdBlock.CodeBlock
        assertNull(code.language)
        assertEquals("plain", code.code)
    }

    @Test
    fun tildeFence() {
        val blocks = parseMarkdown("~~~\na\n~~~")

        val code = blocks.single() as MdBlock.CodeBlock
        assertNull(code.language)
        assertEquals("a", code.code)
    }

    @Test
    fun unterminatedFenceConsumesRestOfText() {
        val blocks = parseMarkdown("text\n```js\nlet a = 1\nlet b = 2")

        assertEquals(2, blocks.size)
        val code = blocks[1] as MdBlock.CodeBlock
        assertEquals("js", code.language)
        assertEquals("let a = 1\nlet b = 2", code.code)
    }

    @Test
    fun parsesMultiLineQuote() {
        val blocks = parseMarkdown("> first\n> second\n\nplain")

        assertEquals(2, blocks.size)
        val quote = blocks[0] as MdBlock.Quote
        assertEquals(2, quote.lines.size)
        assertEquals(listOf(MdSpan.Text("second")), quote.lines[1])
    }

    @Test
    fun parsesHorizontalRules() {
        val blocks = parseMarkdown("---\n\n***\n\n___")

        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it is MdBlock.HorizontalRule })
    }

    @Test
    fun dashesWithSpacesAreHorizontalRule() {
        val blocks = parseMarkdown("- - -")

        assertEquals(listOf(MdBlock.HorizontalRule), blocks)
    }

    @Test
    fun mixedDocumentProducesExpectedBlocks() {
        val markdown = """
            # Title

            Intro **bold**.

            - a
            - b

            > quoted

            ```py
            print(1)
            ```
        """.trimIndent()

        val blocks = parseMarkdown(markdown)

        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.BulletList)
        assertTrue(blocks[3] is MdBlock.Quote)
        assertTrue(blocks[4] is MdBlock.CodeBlock)
    }

    @Test
    fun crlfIsNormalized() {
        val blocks = parseMarkdown("one\r\ntwo\r\n\r\nthree")

        assertEquals(2, blocks.size)
        assertEquals(2, (blocks[0] as MdBlock.Paragraph).lines.size)
    }

    @Test
    fun emptyAndBlankTextProduceNoBlocks() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("   \n\n  \t ").isEmpty())
    }

    @Test
    fun cyrillicAndEmojiAreText() {
        val spans = singleParagraph("Привет 👋 **мир**")[0]

        assertEquals(MdSpan.Text("Привет 👋 "), spans[0])
        assertEquals(MdSpan.Bold(listOf(MdSpan.Text("мир"))), spans[1])
    }

    @Test
    fun inlineCodeKeepsSpacesAroundContent() {
        val spans = parseInline("` spaced `")

        assertEquals(listOf(MdSpan.Code("spaced")), spans)
    }

    private fun singleParagraph(markdown: String): List<List<MdSpan>> {
        val blocks = parseMarkdown(markdown)
        assertEquals(1, blocks.size)
        return (blocks[0] as MdBlock.Paragraph).lines
    }

    @Test
    fun stableSplitEndsAtLastBlankLineOutsideFence() {
        val text = "# Title\n\nFirst paragraph.\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\nTail text"

        val split = splitStableMarkdown(text)

        assertEquals("Tail text", split.tail)
        assertEquals(text, split.stable + split.tail)
    }

    @Test
    fun blankLineInsideOpenFenceIsNotABoundary() {
        val text = "Intro\n\n```\ncode\n\nmore code"

        val split = splitStableMarkdown(text)

        assertEquals("```\ncode\n\nmore code", split.tail)
    }

    @Test
    fun splitParsingEqualsFullParsing() {
        val samples = listOf(
            "1. one\n2. two\n\n3. three\n\n- a\n- b\n\n> quote\n> more\n\nend",
            "Para **bold**\nsecond line\n\n---\n\n## Head\n\n```\nx\n\ny\n```\n\n- item\n  continued",
            "no blank lines at all",
            "trailing blank\n\n",
            "crlf\r\n\r\nnext",
        )
        for (text in samples) {
            val split = splitStableMarkdown(text)
            assertEquals(text, parseMarkdown(split.stable) + parseMarkdown(split.tail), parseMarkdown(text))
        }
    }
}
