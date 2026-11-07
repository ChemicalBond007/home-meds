package com.homemeds.app

import com.homemeds.app.net.ReplySuggestions
import org.junit.Assert.*
import org.junit.Test

class ReplySuggestionsTest {
    private fun block(json: String) = "<suggested_replies>$json</suggested_replies>"

    @Test fun everyChunkBoundaryKeepsMetadataOutOfVisibleText() {
        val response = "请核对药盒。" + block("""["我补拍盒底","我提供已有医嘱"]""") + "尚未保存。"
        for (split in 0..response.length) {
            val filter = ReplySuggestions()
            val outputs = listOf(filter.push(response.take(split)), filter.push(response.drop(split)), filter.push("", true))
            assertEquals("请核对药盒。尚未保存。", outputs.joinToString("") { it.text })
            assertEquals(listOf("我补拍盒底", "我提供已有医嘱"), outputs.mapNotNull { it.replies }.single())
        }
        val filter = ReplySuggestions()
        val outputs = response.map { filter.push(it.toString()) }
        assertEquals("请核对药盒。尚未保存。", outputs.joinToString("") { it.text })
        assertEquals(listOf("我补拍盒底", "我提供已有医嘱"), outputs.mapNotNull { it.replies }.single())
    }

    @Test fun malformedOversizedAndUnfinishedBlocksAreHiddenWithoutInventingReplies() {
        for (json in listOf("not-json", """[42]""", """[""]""", """["${"字".repeat(81)}"]""",
            """["甲","乙","丙","丁"]""", """["${"字".repeat(2000)}"]""")) {
            val result = ReplySuggestions().push("正常文字" + block(json) + "继续回复", true)
            assertEquals("正常文字继续回复", result.text)
            assertNull(result.replies)
        }
        assertEquals("正常文字", ReplySuggestions().push("正常文字<suggested_replies>[\"未完成", true).text)
        assertEquals("正常文字", ReplySuggestions().push("正常文字<suggested_rep", true).text)
    }

    @Test fun acceptsMaximumThreeRepliesAndEightyCharactersAndDeduplicates() {
        val max = "字".repeat(80)
        assertEquals(listOf(max, "补拍", "核对"), ReplySuggestions().push(block("""["$max","补拍","核对"]""")).replies)
        assertEquals(listOf("补拍"), ReplySuggestions().push(block("""[" 补拍 ","补拍"]""")).replies)
        assertEquals(emptyList<String>(), ReplySuggestions().push(block("[]")).replies)
    }

    @Test fun plainRepliesAndComparisonsRemainUnchanged() {
        val text = "请提供药名和时间，数量 < 3 时也需要核对。"
        assertEquals(text, ReplySuggestions().push(text, true).text)
        assertNull(ReplySuggestions().push(text, true).replies)
    }
}
