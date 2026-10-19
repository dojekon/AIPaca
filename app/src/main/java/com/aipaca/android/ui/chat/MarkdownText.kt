package com.aipaca.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    cursor: String = "",
) {
    val blocks = remember(markdown, cursor) {
        parseMarkdown(markdown).withTrailingCursor(cursor)
    }
    if (blocks.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING),
    ) {
        blocks.forEach { block ->
            MarkdownBlock(block, color)
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, color: Color) {
    when (block) {
        is MdBlock.Paragraph -> InlineText(
            lines = block.lines,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )

        is MdBlock.Heading -> InlineText(
            lines = listOf(block.spans),
            color = color,
            style = headingStyle(block.level),
        )

        is MdBlock.BulletList -> ListBlock(block.items, color)

        is MdBlock.OrderedList -> ListBlock(block.items, color)

        is MdBlock.Quote -> QuoteBlock(block.lines, color)

        is MdBlock.CodeBlock -> CodeBlock(block.code, color)

        MdBlock.HorizontalRule -> HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun InlineText(
    lines: List<List<MdSpan>>,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    val annotated = inlineAnnotated(lines, color)
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun ListBlock(items: List<MdListItem>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(LIST_ITEM_SPACING)) {
        items.forEach { item ->
            Row {
                Text(
                    text = item.marker,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.width(LIST_MARKER_WIDTH),
                )
                InlineText(
                    lines = item.lines,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(lines: List<List<MdSpan>>, color: Color) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = QUOTE_BAR_ALPHA)),
        )
        Spacer(Modifier.width(8.dp))
        InlineText(
            lines = lines,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CodeBlock(code: String, color: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle {
    val base = MaterialTheme.typography.bodyMedium
    val scale = when (level) {
        1 -> HEADING_SCALE_1
        2 -> HEADING_SCALE_2
        3 -> HEADING_SCALE_3
        else -> 1f
    }
    return base.copy(
        fontSize = base.fontSize * scale,
        lineHeight = base.lineHeight * scale,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun inlineAnnotated(lines: List<List<MdSpan>>, color: Color): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = CODE_BACKGROUND_ALPHA)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(lines, color, codeBackground, linkColor) {
        buildAnnotatedString {
            lines.forEachIndexed { index, spans ->
                if (index > 0) append('\n')
                appendSpans(spans, SpanStyle(), codeBackground, linkColor)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendSpans(
    spans: List<MdSpan>,
    base: SpanStyle,
    codeBackground: Color,
    linkColor: Color,
) {
    spans.forEach { span ->
        when (span) {
            is MdSpan.Text -> withStyle(base) { append(span.text) }

            is MdSpan.Bold -> appendSpans(
                spans = span.spans,
                base = base.merge(SpanStyle(fontWeight = FontWeight.Bold)),
                codeBackground = codeBackground,
                linkColor = linkColor,
            )

            is MdSpan.Italic -> appendSpans(
                spans = span.spans,
                base = base.merge(SpanStyle(fontStyle = FontStyle.Italic)),
                codeBackground = codeBackground,
                linkColor = linkColor,
            )

            is MdSpan.Strikethrough -> appendSpans(
                spans = span.spans,
                base = base.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)),
                codeBackground = codeBackground,
                linkColor = linkColor,
            )

            is MdSpan.Code -> withStyle(
                base.merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    ),
                ),
            ) {
                append(span.text)
            }

            is MdSpan.Link -> withLink(
                LinkAnnotation.Url(
                    url = span.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                appendSpans(
                    spans = span.spans,
                    base = base.merge(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                    codeBackground = codeBackground,
                    linkColor = linkColor,
                )
            }
        }
    }
}

private fun List<MdBlock>.withTrailingCursor(cursor: String): List<MdBlock> {
    if (cursor.isEmpty()) return this
    if (isEmpty()) {
        return listOf(MdBlock.Paragraph(listOf(listOf(MdSpan.Text(cursor)))))
    }
    val updated = when (val last = last()) {
        is MdBlock.Paragraph -> last.copy(lines = last.lines.appendToLast(MdSpan.Text(cursor)))
        is MdBlock.Heading -> last.copy(spans = last.spans + MdSpan.Text(cursor))
        is MdBlock.BulletList -> last.copy(items = last.items.appendToLastLine(MdSpan.Text(cursor)))
        is MdBlock.OrderedList -> last.copy(items = last.items.appendToLastLine(MdSpan.Text(cursor)))
        is MdBlock.Quote -> last.copy(lines = last.lines.appendToLast(MdSpan.Text(cursor)))
        is MdBlock.CodeBlock -> last.copy(code = last.code + cursor)
        MdBlock.HorizontalRule -> return this + MdBlock.Paragraph(listOf(listOf(MdSpan.Text(cursor))))
    }
    return dropLast(1) + updated
}

private fun List<List<MdSpan>>.appendToLast(span: MdSpan): List<List<MdSpan>> =
    dropLast(1) + listOf(lastOrNull().orEmpty() + span)

private fun List<MdListItem>.appendToLastLine(span: MdSpan): List<MdListItem> {
    val last = lastOrNull() ?: return this
    return dropLast(1) + last.copy(lines = last.lines.appendToLast(span))
}

private val BLOCK_SPACING = 6.dp
private val LIST_ITEM_SPACING = 2.dp
private val LIST_MARKER_WIDTH = 24.dp
private const val QUOTE_BAR_ALPHA = 0.5f
private const val CODE_BACKGROUND_ALPHA = 0.12f
private const val HEADING_SCALE_1 = 1.25f
private const val HEADING_SCALE_2 = 1.15f
private const val HEADING_SCALE_3 = 1.08f
