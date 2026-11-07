package com.homemeds.app.net

import org.json.JSONArray

/** Separates optional model-generated reply metadata from streamed, visible text. */
internal class ReplySuggestions {
    data class Output(val text: String, val replies: List<String>?)
    private var pending = ""
    private var inside = false
    private val body = StringBuilder()
    private var oversized = false

    fun push(chunk: String, final: Boolean = false): Output {
        pending += chunk
        val visible = StringBuilder()
        var replies: List<String>? = null
        fun capture(text: String) {
            if (body.length + text.length <= 1024 && !oversized) body.append(text)
            else { oversized = true; body.clear() }
        }
        while (pending.isNotEmpty()) {
            val tag = if (inside) CLOSE else OPEN
            val at = pending.indexOf(tag)
            if (at >= 0) {
                if (inside) {
                    capture(pending.take(at))
                    if (!oversized) replies = parse(body.toString())
                } else visible.append(pending.take(at))
                pending = pending.drop(at + tag.length)
                inside = !inside
                body.clear(); oversized = false
            } else {
                val hold = (1 until tag.length).lastOrNull { pending.endsWith(tag.take(it)) } ?: 0
                val ready = pending.dropLast(hold)
                if (inside) capture(ready) else visible.append(ready)
                pending = pending.takeLast(hold)
                break
            }
        }
        if (final) { pending = ""; inside = false; body.clear(); oversized = false }
        return Output(visible.toString(), replies)
    }

    private fun parse(text: String): List<String>? = runCatching {
        val array = JSONArray(text.trim())
        if (array.length() > 3) return@runCatching null
        (0 until array.length()).map { index ->
            (array.get(index) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }
                ?: return@runCatching null
        }.distinct()
    }.getOrNull()

    companion object {
        private const val OPEN = "<suggested_replies>"
        private const val CLOSE = "</suggested_replies>"
        const val PROMPT = "根据本轮对话，可在回复末尾附加 <suggested_replies>[\"用户可选的回复\"]</suggested_replies>。" +
            "标签不放进代码块，不在正文中解释标签。" +
            "数组为 0 到 3 条简短中文回复，每条不超过 80 字；用于让用户补充或确认信息，不是给用户的用药建议。" +
            "不能编造用户的药名、用量、时间或医嘱，不确定时可建议用户提供实拍或已有医嘱；无需选择时返回空数组。" +
            "这些是可选回复，不代表用户已作答或同意保存。信息充分且用户已表达记录意图时主动调用草稿工具，不要等待用户点击生成按钮；保存仍需人工确认。"
    }
}
