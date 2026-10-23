package com.aipaca.android.engine.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test
    fun dropsScriptsStylesAndHeadButKeepsTitle() {
        val doc = htmlToText(
            "<!DOCTYPE html><html><head><title>Hi &amp; bye</title><style>p{color:red}</style></head>" +
                "<body><script type=\"x\">alert(1)</script><p>Visible</p><!-- hidden --></body></html>",
        )

        assertEquals("Hi & bye", doc.title)
        assertEquals("Visible", doc.text)
    }

    @Test
    fun blocksBecomeParagraphsAndListsHeadingsAreMarked() {
        val doc = htmlToText("<h2>Title</h2><p>One<br>Two</p><ol><li>a</li><li>b</li></ol><div>Tail</div>")

        assertEquals("## Title\n\nOne\nTwo\n\n- a\n- b\n\nTail", doc.text)
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("a < b & \"c\" — é ☃ x", decodeEntities("a &lt; b &amp; &quot;c&quot; &mdash; &#233; &#x2603; x"))
        assertEquals("&unknown;", decodeEntities("&unknown;"))
    }

    @Test
    fun tableCellsAreSeparated() {
        assertTrue(htmlToText("<table><tr><td>1</td><td>2</td></tr></table>").text.startsWith("1 | 2"))
    }

    @Test
    fun htmlDetection() {
        assertTrue(looksLikeHtml("text/html; charset=utf-8", "x"))
        assertTrue(looksLikeHtml(null, "  <!doctype html><html>"))
        assertFalse(looksLikeHtml("application/json", "{\"a\":1}"))
    }

    @Test
    fun pagingRespectsBoundsAndSurrogates() {
        val page = pageOf("abcdef", offset = 2, maxChars = 3)
        assertEquals("cde", page.text)
        assertTrue(page.hasMore)
        assertFalse(pageOf("abc", offset = 10, maxChars = 5).hasMore)
        val emoji = "a😀b"
        assertEquals("a", pageOf(emoji, 0, 2).text)
    }

    @Test
    fun quotedGreaterThanInsideAttributeDoesNotLeakMarkup() {
        val doc = htmlToText("""<p data-mw='{"a":"<br/>x>y"}' title="1 > 0">Clean</p>""")

        assertEquals("Clean", doc.text)
    }

    @Test
    fun mainContentIsPreferredOverPageChrome() {
        val doc = htmlToText("<body><div>Site menu</div><main><p>Article body</p></main><div>Cookie banner</div></body>")

        assertEquals("Article body", doc.text)
    }

    @Test
    fun linkDenseListsAreDroppedButContentListsKept() {
        val links = (1..6).joinToString("") { "<li><a href=\"/l$it\">Lang $it</a></li>" }
        val doc = htmlToText("<ul>$links</ul><ul><li>Real point one</li><li>Real point two</li></ul>")

        assertFalse(doc.text.contains("Lang"))
        assertTrue(doc.text.contains("- Real point one"))
    }

    @Test
    fun longAttributeDoesNotOverflowSmallStack() {
        val html = "<p data-x=\"" + "a>".repeat(200_000) + "\">ok</p>"
        var text: String? = null
        val thread = Thread(null, { text = htmlToText(html).text }, "small-stack", 128 * 1024)
        thread.start()
        thread.join()

        assertEquals("ok", text)
    }
}
