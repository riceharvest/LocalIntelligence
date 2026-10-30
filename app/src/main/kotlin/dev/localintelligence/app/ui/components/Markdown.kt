package dev.localintelligence.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
                    Text(block.marker, style = style, color = color, modifier = Modifier.width(24.dp))
                    Text(inline(block.text, codeBackground, linkColor), style = style, color = color)
                }
                is Block.Code -> Surface(
                    color = codeBackground,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                        softWrap = false,
                    )
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
}

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
