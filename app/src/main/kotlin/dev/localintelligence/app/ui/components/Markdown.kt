package dev.localintelligence.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import dev.localintelligence.app.R
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

/**
 * The model's answer, with the markdown it writes drawn as formatting instead of
 * as `**` and `#` characters.
 *
 * A small parser rather than a library: models write a handful of constructs
 * (paragraphs, headings, bullet and numbered lists, code blocks, bold, italic,
 * inline code, links), and a renderer for those is a page of code where a
 * library would add its own parser and transitive dependencies to the APK.
 * Anything it does not recognise stays as the plain text the model wrote, so a
 * half-streamed answer renders the same way as a finished one.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> Text(
                    text = inline(block.text, codeBackground, linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                )
                is Block.Paragraph -> Text(inline(block.text, codeBackground, linkColor), style = style, color = color)
                is Block.Quote -> Text(
                    text = inline(block.text, codeBackground, linkColor),
                    style = style.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
                is Block.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    // A minimum, not a width: "10." at a large font size grows the column instead of clipping.
                    Text(
                        block.marker,
                        style = style,
                        color = color,
                        softWrap = false,
                        modifier = Modifier.widthIn(min = 24.dp).padding(end = 4.dp),
                    )
                    Text(inline(block.text, codeBackground, linkColor), style = style, color = color)
                }
                is Block.Table -> MarkdownTable(block, style, color, codeBackground, linkColor)
                is Block.Code -> CodeBlock(block.text, codeBackground)
            }
        }
    }
}

/**
 * A code block: monospace, always left-to-right (code reads the same in an Arabic or Hebrew
 * chat), scrolls sideways instead of wrapping, and has its own copy button so a snippet can
 * be taken without selecting it.
 */
@Composable
private fun CodeBlock(text: String, codeBackground: Color) {
    val clipboard = LocalClipboardManager.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            color = codeBackground,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    softWrap = false,
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.simple_copy_code),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * A pipe table, scrolled sideways as one piece. Each cell has a minimum width and may grow to a
 * maximum, so a narrow phone scrolls the table rather than squeezing every column into a few dp.
 */
@Composable
private fun MarkdownTable(
    table: Block.Table,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    linkColor: Color,
) {
    val cellStyle = style.copy(fontSize = style.fontSize * 0.92f)
    Surface(
        color = codeBackground,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(8.dp)) {
            table.rows.forEachIndexed { rowIndex, cells ->
                Row {
                    cells.forEach { cell ->
                        Text(
                            text = inline(cell, codeBackground, linkColor),
                            style = if (rowIndex == 0) cellStyle.copy(fontWeight = FontWeight.SemiBold) else cellStyle,
                            color = color,
                            modifier = Modifier
                                .widthIn(min = 72.dp, max = 220.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class ListItem(val marker: String, val text: String, val indent: Int) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
    /** Header row first; the separator row (`|---|`) is dropped. */
    data class Table(val rows: List<List<String>>) : Block
}

/** A pipe-table row: starts and ends with a pipe. */
private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")

private fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
private val QUOTE = Regex("""^>\s?(.*)$""")
private val FENCE = Regex("""^\s*```""")

private fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (FENCE.containsMatchIn(line)) {
            flush()
            val code = StringBuilder()
            i++
            // An unclosed fence (still streaming) runs to the end of the text.
            while (i < lines.size && !FENCE.containsMatchIn(lines[i])) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            blocks += Block.Code(code.toString())
            i++
            continue
        }
        val heading = HEADING.find(line)
        val bullet = BULLET.find(line)
        val numbered = NUMBERED.find(line)
        val quote = QUOTE.find(line)
        when {
            // A table is a header row, a separator row and body rows. The header must be followed by
            // a separator, so a line that merely contains a pipe stays a paragraph.
            TABLE_ROW.containsMatchIn(line) && i + 1 < lines.size && TABLE_SEPARATOR.containsMatchIn(lines[i + 1]) -> {
                flush()
                val rows = mutableListOf(splitRow(line))
                i += 2
                while (i < lines.size && TABLE_ROW.containsMatchIn(lines[i])) {
                    rows += splitRow(lines[i])
                    i++
                }
                blocks += Block.Table(rows)
                continue
            }
            line.isBlank() -> flush()
            heading != null -> {
                flush()
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            bullet != null -> {
                flush()
                blocks += Block.ListItem("•", bullet.groupValues[2], bullet.groupValues[1].length / 2)
            }
            numbered != null -> {
                flush()
                blocks += Block.ListItem(numbered.groupValues[2] + ".", numbered.groupValues[3], numbered.groupValues[1].length / 2)
            }
            quote != null -> {
                flush()
                blocks += Block.Quote(quote.groupValues[1])
            }
            else -> {
                // A single line break inside a paragraph is kept: models use it
                // for short lines (an address, a list without markers).
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flush()
    return blocks
}

// Inline spans, first match wins at each position: code, link, bold, italic.
private val INLINE = Regex(
    """`([^`]+)`|\[([^\]]+)\]\((https?://[^)\s]+)\)|\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""",
)

private fun inline(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (match in INLINE.findAll(text)) {
        append(text, at, match.range.first)
        val g = match.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(g[1])
            }
            g[2].isNotEmpty() -> withLink(
                LinkAnnotation.Url(
                    g[3],
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(g[2]) }
            g[4].isNotEmpty() || g[5].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(g[4].ifEmpty { g[5] })
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6].ifEmpty { g[7] }) }
        }
        at = match.range.last + 1
    }
    append(text, at, text.length)
}
