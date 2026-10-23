package com.aipaca.android.engine.agent.tools

data class HtmlDocumentText(val title: String?, val text: String)

private const val ATTRS = "(?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+"

private val DROP_BLOCKS = Regex(
    "<(script|style|noscript|svg|template|head|iframe|canvas|nav|footer|aside|form)\\b$ATTRS>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val MAIN_CONTENT = listOf("main", "article").map { tag ->
    Regex("<$tag\\b$ATTRS>(.*)</$tag\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
}
private val ANCHOR = Regex("<a\\b$ATTRS>(.*?)</a\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private const val LINK_LIST_MIN_LINKS = 5
private const val LINK_LIST_DENSITY = 0.8
private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val TITLE = Regex("<title\\b$ATTRS>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HEADING_OPEN = Regex("<h([1-6])\\b$ATTRS>", RegexOption.IGNORE_CASE)
private val LIST_ITEM = Regex("<li\\b$ATTRS>", RegexOption.IGNORE_CASE)
private val LINE_BREAK = Regex("<br\\b$ATTRS>", RegexOption.IGNORE_CASE)
private val CELL = Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE)
private val BLOCK_TAG = Regex(
    "</?(p|div|section|article|main|header|footer|nav|aside|ul|ol|tr|table|thead|tbody|blockquote|pre|" +
        "h[1-6]|dl|dt|dd|figure|figcaption|form|hr)\\b$ATTRS>",
    RegexOption.IGNORE_CASE,
)
private val ANY_TAG = Regex("<[!/?a-zA-Z]$ATTRS>")
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")
private val NAMED_ENTITY = Regex("&([a-zA-Z]+);")
private val SPACES = Regex("[ \\t\\u00A0\\f\\r]+")
private val SPACE_AROUND_NEWLINE = Regex(" *\\n *")
private val MANY_NEWLINES = Regex("\\n{3,}")
private val HTML_MARKERS = listOf("<!doctype html", "<html", "<head", "<body")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
    "copy" to "©", "reg" to "®", "trade" to "™", "rsquo" to "’", "lsquo" to "‘",
    "rdquo" to "”", "ldquo" to "“", "middot" to "·", "bull" to "•", "times" to "×",
)

fun looksLikeHtml(contentType: String?, body: String): Boolean {
    if (contentType?.contains("html", ignoreCase = true) == true) return true
    val head = body.take(512).trimStart().lowercase()
    return HTML_MARKERS.any { head.startsWith(it) || head.contains(it) }
}

fun htmlToText(html: String): HtmlDocumentText {
    val title = TITLE.find(html)?.groupValues?.get(1)?.let { decodeEntities(ANY_TAG.replace(it, "")).trim() }
        ?.ifEmpty { null }
    val content = MAIN_CONTENT.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: html
    var text = COMMENTS.replace(content, " ")
    text = DROP_BLOCKS.replace(text, " ")
    text = removeLinkLists(text)
    text = HEADING_OPEN.replace(text) { "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " }
    text = LIST_ITEM.replace(text, "\n- ")
    text = LINE_BREAK.replace(text, "\n")
    text = CELL.replace(text, " | ")
    text = BLOCK_TAG.replace(text, "\n\n")
    text = ANY_TAG.replace(text, " ")
    text = decodeEntities(text)
    text = SPACES.replace(text, " ")
    text = SPACE_AROUND_NEWLINE.replace(text, "\n")
    text = MANY_NEWLINES.replace(text, "\n\n")
    return HtmlDocumentText(title = title, text = text.trim())
}

fun decodeEntities(text: String): String {
    val numeric = NUMERIC_ENTITY.replace(text) { match ->
        val radix = if (match.groupValues[1].isNotEmpty()) 16 else 10
        val code = match.groupValues[2].toIntOrNull(radix)
        if (code == null || code !in 1..0x10FFFF) match.value else String(Character.toChars(code))
    }
    return NAMED_ENTITY.replace(numeric) { match -> NAMED_ENTITIES[match.groupValues[1]] ?: match.value }
}

data class TextPage(val text: String, val offset: Int, val end: Int, val total: Int) {
    val hasMore: Boolean get() = end < total
}

fun pageOf(text: String, offset: Int, maxChars: Int): TextPage {
    val start = offset.coerceIn(0, text.length)
    var end = (start + maxChars.coerceAtLeast(1)).coerceAtMost(text.length)
    if (end < text.length && end > start && text[end - 1].isHighSurrogate()) end--
    return TextPage(text.substring(start, end), start, end, text.length)
}

private fun isLinkList(inner: String): Boolean {
    val anchors = ANCHOR.findAll(inner).toList()
    if (anchors.size < LINK_LIST_MIN_LINKS) return false
    val linkChars = anchors.sumOf { ANY_TAG.replace(it.groupValues[1], "").trim().length }
    val totalChars = ANY_TAG.replace(inner, "").trim().length
    return totalChars > 0 && linkChars >= totalChars * LINK_LIST_DENSITY
}

private fun removeLinkLists(html: String): String {
    val text = StringBuilder(html)
    val lower = StringBuilder(html.length).apply { html.forEach { append(it.lowercaseChar()) } }
    var from = 0
    while (true) {
        val close = listOf(lower.indexOf("</ul", from), lower.indexOf("</ol", from)).filter { it >= 0 }.minOrNull()
            ?: break
        val closeEnd = lower.indexOf(">", close).let { if (it < 0) lower.length else it + 1 }
        val open = maxOf(lower.lastIndexOf("<ul", close), lower.lastIndexOf("<ol", close))
        val openEnd = if (open >= 0) lower.indexOf(">", open) else -1
        if (open < 0 || openEnd < 0 || openEnd > close) {
            from = closeEnd
            continue
        }
        if (isLinkList(text.substring(openEnd + 1, close))) {
            text.replace(open, closeEnd, " ")
            lower.replace(open, closeEnd, " ")
            from = open
        } else {
            from = closeEnd
        }
    }
    return text.toString()
}
