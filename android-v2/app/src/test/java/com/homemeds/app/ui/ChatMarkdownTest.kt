package com.homemeds.app.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.*
import org.junit.Assert.*
import org.junit.Test

class ChatMarkdownTest {
    @Test fun reportedBulletAndBoldLabelAreParsedWithoutShowingMarkup() {
        val list = parseChatMarkdown("* **药品**：演示药品").firstChild
        assertTrue(list is BulletList)
        val inline = markdownInline(list.firstChild.firstChild)
        assertEquals("药品：演示药品", inline.text)
        assertTrue(inline.spanStyles.any { it.start == 0 && it.end == 2 && it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun nestedEmphasisEscapesAndInlineCodeKeepTheirMeaning() {
        val inline = markdownInline(parseChatMarkdown("**加粗 *斜体* 文本**，`a * b`，\\*原样\\*，A_B_C").firstChild)
        assertEquals("加粗 斜体 文本，a * b，*原样*，A_B_C", inline.text)
        assertTrue(inline.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(inline.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(inline.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test fun listsHeadingsQuotesAndTablesUseStructuredBlocks() {
        val document = parseChatMarkdown("# 标题\n\n3. 第三项\n4. 第四项\n\n> 引用\n\n| 药品 | 备注 |\n| --- | --- |\n| 演示 | 核对实物 |")
        val heading = document.firstChild
        assertTrue(heading is Heading)
        val ordered = heading.next as OrderedList
        assertEquals(3, ordered.markerStartNumber)
        assertTrue(ordered.next is BlockQuote)
        assertTrue(ordered.next.next is TableBlock)
    }

    @Test fun codeFenceDoesNotInterpretMarkdownInsideIt() {
        val code = parseChatMarkdown("```text\n* **原样代码**\n```").firstChild as FencedCodeBlock
        assertEquals("* **原样代码**\n", code.literal)
    }

    @Test fun onlyPublicWebLinksAreClickableAndImagesAreTextOnly() {
        val public = markdownInline(parseChatMarkdown("[说明书](https://example.com/info)").firstChild)
        assertEquals("说明书", public.text)
        assertEquals(1, public.getLinkAnnotations(0, public.length).size)
        for (url in listOf("javascript:alert", "file:///etc/passwd", "intent://open", "https://127.0.0.1/x",
            "https://user:pass@example.com/x", "https://private.local/x")) {
            val blocked = markdownInline(parseChatMarkdown("[禁止]($url)").firstChild)
            assertEquals("禁止", blocked.text)
            assertTrue(blocked.getLinkAnnotations(0, blocked.length).isEmpty())
        }
        val image = markdownInline(parseChatMarkdown("![药盒](https://example.com/tracker.png)").firstChild)
        assertEquals("[图片：药盒]", image.text)
        assertTrue(image.getLinkAnnotations(0, image.length).isEmpty())
    }

    @Test fun incompleteStreamingSyntaxIsSafeAndBecomesFormattedWhenCompleted() {
        for (partial in listOf("* **药", "* **药品", "* **药品**：")) {
            assertNotNull(parseChatMarkdown(partial))
        }
        assertEquals("药品：演示", markdownInline(parseChatMarkdown("* **药品**：演示").firstChild.firstChild.firstChild).text)
    }
}
