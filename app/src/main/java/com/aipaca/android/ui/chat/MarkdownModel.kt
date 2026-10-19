package com.aipaca.android.ui.chat

sealed interface MdBlock {
    data class Paragraph(val lines: List<List<MdSpan>>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class BulletList(val items: List<MdListItem>) : MdBlock
    data class OrderedList(val items: List<MdListItem>) : MdBlock
    data class Quote(val lines: List<List<MdSpan>>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    object HorizontalRule : MdBlock
}

data class MdListItem(val marker: String, val lines: List<List<MdSpan>>)

sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val spans: List<MdSpan>) : MdSpan
    data class Italic(val spans: List<MdSpan>) : MdSpan
    data class Strikethrough(val spans: List<MdSpan>) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Link(val spans: List<MdSpan>, val url: String) : MdSpan
}

fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<List<MdSpan>>()
    val quote = mutableListOf<List<MdSpan>>()
    val items = mutableListOf<MdListItem>()
    val code = mutableListOf<String>()
    var listKind: ListKind? = null
    var fence: Fence? = null

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toList())
            paragraph.clear()
        }
    }

    fun flushQuote() {
        if (quote.isNotEmpty()) {
            blocks += MdBlock.Quote(quote.toList())
            quote.clear()
        }
    }

    fun flushList() {
        when (listKind) {
            ListKind.BULLET -> if (items.isNotEmpty()) blocks += MdBlock.BulletList(items.toList())
            ListKind.ORDERED -> if (items.isNotEmpty()) blocks += MdBlock.OrderedList(items.toList())
            null -> Unit
        }
        items.clear()
        listKind = null
    }

    fun flushAll() {
        flushParagraph()
        flushQuote()
        flushList()
    }

    for (raw in lines) {
        val line = raw.trimStart()
        val activeFence = fence
        if (activeFence != null) {
            if (isFenceClose(line, activeFence)) {
                blocks += MdBlock.CodeBlock(activeFence.language, code.joinToString("\n"))
                code.clear()
                fence = null
            } else {
                code += raw
            }
            continue
        }
        if (line.isBlank()) {
            flushAll()
            continue
        }
        val opening = fenceOpening(line)
        if (opening != null) {
            flushAll()
            fence = opening
            continue
        }
        val heading = headingLine(line)
        if (heading != null) {
            flushAll()
            blocks += MdBlock.Heading(heading.first, parseInline(heading.second))
            continue
        }
        if (isHorizontalRule(line)) {
            flushAll()
            blocks += MdBlock.HorizontalRule
            continue
        }
        if (line.startsWith(">")) {
            flushParagraph()
            flushList()
            quote += parseInline(stripQuoteMarker(line))
            continue
        }
        val bullet = BULLET_REGEX.matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            flushQuote()
            if (listKind != ListKind.BULLET) {
                flushList()
                listKind = ListKind.BULLET
            }
            items += MdListItem(BULLET_MARKER, listOf(parseInline(bullet.groupValues[1])))
            continue
        }
        val ordered = ORDERED_REGEX.matchEntire(line)
        if (ordered != null) {
            flushParagraph()
            flushQuote()
            if (listKind != ListKind.ORDERED) {
                flushList()
                listKind = ListKind.ORDERED
            }
            val marker = ordered.groupValues[1] + ordered.groupValues[2]
            items += MdListItem(marker, listOf(parseInline(ordered.groupValues[3])))
            continue
        }
        if (items.isNotEmpty()) {
            val last = items.removeAt(items.lastIndex)
            items += last.copy(lines = last.lines + listOf(parseInline(line)))
            continue
        }
        flushQuote()
        paragraph += parseInline(line)
    }

    val danglingFence = fence
    if (danglingFence != null) {
        blocks += MdBlock.CodeBlock(danglingFence.language, code.joinToString("\n"))
    }
    flushAll()
    return blocks
}

fun parseInline(text: String): List<MdSpan> = parseInline(text, 0)

private fun parseInline(text: String, depth: Int): List<MdSpan> {
    if (depth >= MAX_INLINE_DEPTH) return listOf(MdSpan.Text(text))
    val out = mutableListOf<MdSpan>()
    val buffer = StringBuilder()

    fun flushBuffer() {
        if (buffer.isNotEmpty()) {
            out += MdSpan.Text(buffer.toString())
            buffer.clear()
        }
    }

    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE_CHARS -> {
                buffer.append(text[index + 1])
                index += 2
            }

            char == '`' -> {
                val run = runLength(text, index, '`')
                val close = closingBacktickRun(text, index + run, run)
                if (close >= 0) {
                    flushBuffer()
                    out += MdSpan.Code(codeSpanContent(text.substring(index + run, close)))
                    index = close + run
                } else {
                    repeat(run) { buffer.append('`') }
                    index += run
                }
            }

            text.startsWith(COMBINED_STRONG, index) -> {
                val parsed = emphasis(text, index, COMBINED_STRONG, depth) { inner ->
                    MdSpan.Bold(listOf(MdSpan.Italic(inner)))
                }
                if (parsed != null) {
                    flushBuffer()
                    out += parsed.first
                    index = parsed.second
                } else {
                    buffer.append(COMBINED_STRONG)
                    index += COMBINED_STRONG.length
                }
            }

            text.startsWith(STRONG, index) -> {
                val parsed = emphasis(text, index, STRONG, depth) { MdSpan.Bold(it) }
                if (parsed != null) {
                    flushBuffer()
                    out += parsed.first
                    index = parsed.second
                } else {
                    buffer.append(STRONG)
                    index += STRONG.length
                }
            }

            text.startsWith(STRONG_UNDERSCORE, index) -> {
                val parsed = emphasis(text, index, STRONG_UNDERSCORE, depth) { MdSpan.Bold(it) }
                if (parsed != null) {
                    flushBuffer()
                    out += parsed.first
                    index = parsed.second
                } else {
                    buffer.append(STRONG_UNDERSCORE)
                    index += STRONG_UNDERSCORE.length
                }
            }

            text.startsWith(STRIKETHROUGH, index) -> {
                val parsed = emphasis(text, index, STRIKETHROUGH, depth) { MdSpan.Strikethrough(it) }
                if (parsed != null) {
                    flushBuffer()
                    out += parsed.first
                    index = parsed.second
                } else {
                    buffer.append(STRIKETHROUGH)
                    index += STRIKETHROUGH.length
                }
            }

            char == '*' -> {
                val parsed = emphasis(text, index, "*", depth) { MdSpan.Italic(it) }
                if (parsed != null) {
                    flushBuffer()
                    out += parsed.first
                    index = parsed.second
                } else {
                    buffer.append(char)
                    index++
                }
            }

            char == '_' -> {
                val parsed = emphasis(text, index, "_", depth) { MdSpan.Italic(it) }
                if (parsed != null) {
                    flushBuffer()
                    out += parsed.first
                    index = parsed.second
                } else {
                    buffer.append(char)
                    index++
                }
            }

            char == '[' -> {
                val parsed = link(text, index, depth)
                if (parsed != null) {
                    flushBuffer()
                    out += parsed.first
                    index = parsed.second
                } else {
                    buffer.append(char)
                    index++
                }
            }

            else -> {
                buffer.append(char)
                index++
            }
        }
    }
    flushBuffer()
    return mergeTextSpans(out)
}

private fun emphasis(
    text: String,
    start: Int,
    delimiter: String,
    depth: Int,
    wrap: (List<MdSpan>) -> MdSpan,
): Pair<MdSpan, Int>? {
    if (!isOpeningDelimiter(text, start, delimiter)) return null
    val close = closingDelimiter(text, start + delimiter.length, delimiter)
    if (close < 0) return null
    val inner = parseInline(text.substring(start + delimiter.length, close), depth + 1)
    return wrap(inner) to close + delimiter.length
}

private fun isOpeningDelimiter(text: String, start: Int, delimiter: String): Boolean {
    val after = text.getOrNull(start + delimiter.length) ?: return false
    if (after.isWhitespace()) return false
    if (delimiter[0] == '_') {
        val before = text.getOrNull(start - 1)
        if (before != null && before.isLetterOrDigit()) return false
    }
    return true
}

private fun closingDelimiter(text: String, from: Int, delimiter: String): Int {
    var index = text.indexOf(delimiter, from)
    while (index >= 0) {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + delimiter.length)
        val validBefore = before != null && !before.isWhitespace() && before != delimiter[0]
        val validAfter = after != delimiter[0]
        if (validBefore && validAfter) return index
        index = text.indexOf(delimiter, index + 1)
    }
    return -1
}

private fun link(text: String, start: Int, depth: Int): Pair<MdSpan.Link, Int>? {
    val labelEnd = text.indexOf(']', start + 1)
    if (labelEnd < 0) return null
    if (text.getOrNull(labelEnd + 1) != '(') return null
    val urlEnd = text.indexOf(')', labelEnd + 2)
    if (urlEnd < 0) return null
    val rawUrl = text.substring(labelEnd + 2, urlEnd).trim()
    val url = rawUrl.substringBefore(' ').substringBefore('\t')
    if (url.isEmpty()) return null
    val label = parseInline(text.substring(start + 1, labelEnd), depth + 1)
    if (label.isEmpty()) return null
    return MdSpan.Link(label, url) to urlEnd + 1
}

private fun closingBacktickRun(text: String, from: Int, length: Int): Int {
    var index = from
    while (index < text.length) {
        if (text[index] == '`') {
            val run = runLength(text, index, '`')
            if (run == length) return index
            index += run
        } else {
            index++
        }
    }
    return -1
}

private fun codeSpanContent(content: String): String {
    if (
        content.length >= 2 &&
        content.first() == ' ' &&
        content.last() == ' ' &&
        content.any { it != ' ' }
    ) {
        return content.substring(1, content.length - 1)
    }
    return content
}

private fun runLength(text: String, start: Int, char: Char): Int {
    var length = 0
    while (start + length < text.length && text[start + length] == char) length++
    return length
}

private fun mergeTextSpans(spans: List<MdSpan>): List<MdSpan> {
    val merged = mutableListOf<MdSpan>()
    for (span in spans) {
        val last = merged.lastOrNull()
        if (span is MdSpan.Text && last is MdSpan.Text) {
            merged[merged.lastIndex] = MdSpan.Text(last.text + span.text)
        } else {
            merged += span
        }
    }
    return merged
}

private class Fence(val char: Char, val length: Int, val language: String?)

private enum class ListKind { BULLET, ORDERED }

private val HEADING_REGEX = Regex("^(#{1,6})(?:[ \t]+(.*?))?[ \t]*$")
private val BULLET_REGEX = Regex("^[-*+][ \t]+(.*)$")
private val ORDERED_REGEX = Regex("^(\\d{1,9})([.)])[ \t]+(.*)$")
private val CLOSING_HASHES_REGEX = Regex("[ \t]+#+$")

private fun headingLine(line: String): Pair<Int, String>? {
    val match = HEADING_REGEX.matchEntire(line) ?: return null
    val content = match.groupValues[2].trim()
    return match.groupValues[1].length to content.replace(CLOSING_HASHES_REGEX, "")
}

private fun fenceOpening(line: String): Fence? {
    val char = when {
        line.startsWith("```") -> '`'
        line.startsWith("~~~") -> '~'
        else -> return null
    }
    val length = runLength(line, 0, char)
    val rest = line.substring(length).trim()
    val language = rest.substringBefore(' ').ifEmpty { null }
    return Fence(char, length, language)
}

private fun isFenceClose(line: String, fence: Fence): Boolean {
    val trimmed = line.trimEnd()
    if (trimmed.isEmpty()) return false
    val run = runLength(trimmed, 0, fence.char)
    return run >= fence.length && run == trimmed.length
}

private fun isHorizontalRule(line: String): Boolean {
    val compact = line.filterNot { it == ' ' || it == '\t' }
    if (compact.length < HORIZONTAL_RULE_MIN) return false
    val first = compact[0]
    if (first != '-' && first != '*' && first != '_') return false
    return compact.all { it == first }
}

private fun stripQuoteMarker(line: String): String {
    val rest = line.substring(1)
    return if (rest.startsWith(" ")) rest.substring(1) else rest
}

private const val COMBINED_STRONG = "***"
private const val STRONG = "**"
private const val STRONG_UNDERSCORE = "__"
private const val STRIKETHROUGH = "~~"
private const val BULLET_MARKER = "•"
private const val MAX_INLINE_DEPTH = 8
private const val HORIZONTAL_RULE_MIN = 3
private const val ESCAPABLE_CHARS = "\\`*_{}[]()#+-.!>~|"
