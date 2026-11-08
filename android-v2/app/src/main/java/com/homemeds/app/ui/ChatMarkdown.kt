package com.homemeds.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.homemeds.app.UrlPolicy
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser

/** Native text only: no WebView, HTML execution, or automatic remote image requests. */
private val chatMarkdownParser = Parser.builder().extensions(listOf(
    TablesExtension.create(), StrikethroughExtension.create(),
)).build()

internal fun parseChatMarkdown(source: String): Node = chatMarkdownParser.parse(source)

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

internal fun markdownInline(parent: Node): AnnotatedString = buildAnnotatedString {
    // Kept in a local visitor so nested emphasis and links preserve their styles.
    parent.accept(object : AbstractVisitor() {
        override fun visit(text: MarkdownText) { append(text.literal) }
        override fun visit(softLineBreak: SoftLineBreak) { append("\n") }
        override fun visit(hardLineBreak: HardLineBreak) { append("\n") }
        override fun visit(code: Code) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Cream)) { append(code.literal) }
        }
        override fun visit(emphasis: Emphasis) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { visitChildren(emphasis) }
        }
        override fun visit(strong: StrongEmphasis) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { visitChildren(strong) }
        }
        override fun visit(link: Link) {
            val url = runCatching { UrlPolicy.publicUrl(link.destination).toString() }.getOrNull()
            if (url == null) visitChildren(link)
            else withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Green, textDecoration = TextDecoration.Underline)))) {
                visitChildren(link)
            }
        }
        override fun visit(image: Image) {
            append("[图片："); visitChildren(image); append("]")
        }
        override fun visit(html: HtmlInline) { append(html.literal) }
        override fun visit(customNode: CustomNode) {
            if (customNode is Strikethrough) withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                visitChildren(customNode)
            } else visitChildren(customNode)
        }
    })
}

/** Parse both completed messages and the current stream using exactly the same renderer. */
@Composable internal fun ChatMarkdown(source: String) {
    val document = remember(source) { parseChatMarkdown(source) }
    SelectionContainer { MarkdownBlocks(document) }
}

@Composable private fun MarkdownBlocks(parent: Node) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parent.children().forEach { node ->
            when (node) {
                is Paragraph -> Text(markdownInline(node), style = MaterialTheme.typography.bodyLarge)
                is Heading -> Text(markdownInline(node), fontWeight = FontWeight.Bold, style = when (node.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.bodyLarge
                })
                is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val start = (node as? OrderedList)?.markerStartNumber ?: 1
                    node.children().forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (node is OrderedList) "${start + index}." else "•",
                                style = MaterialTheme.typography.bodyLarge)
                            Box(Modifier.weight(1f)) { MarkdownBlocks(item) }
                        }
                    }
                }
                is BlockQuote -> Surface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = Cream) {
                    Box(Modifier.padding(10.dp)) { MarkdownBlocks(node) }
                }
                is FencedCodeBlock -> MarkdownCode(node.literal)
                is IndentedCodeBlock -> MarkdownCode(node.literal)
                is ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                is TableBlock -> MarkdownTable(node)
                is HtmlBlock -> Text(node.literal, style = MaterialTheme.typography.bodyLarge)
                else -> MarkdownBlocks(node)
            }
        }
    }
}

@Composable private fun MarkdownCode(code: String) {
    Surface(Modifier.fillMaxWidth(), color = Cream, shape = MaterialTheme.shapes.small) {
        Text(code.trimEnd('\n'), Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun MarkdownTable(table: TableBlock) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column {
            table.children().flatMap { it.children() }.forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    row.children().filterIsInstance<TableCell>().forEach { cell ->
                        Surface(Modifier.width(160.dp).fillMaxHeight(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = if (cell.isHeader) Cream else MaterialTheme.colorScheme.surface) {
                            Text(markdownInline(cell), Modifier.padding(8.dp),
                                fontWeight = if (cell.isHeader) FontWeight.Bold else FontWeight.Normal,
                                textAlign = when (cell.alignment) {
                                    TableCell.Alignment.CENTER -> TextAlign.Center
                                    TableCell.Alignment.RIGHT -> TextAlign.Right
                                    else -> TextAlign.Left
                                }, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
