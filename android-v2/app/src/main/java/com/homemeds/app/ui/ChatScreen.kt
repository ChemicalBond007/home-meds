package com.homemeds.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.homemeds.app.*
import com.homemeds.app.net.AiAgent
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(val role: String, val content: String)
data class Proposal(val id: String, val operation: String, val planId: String?, val draft: JSONObject)
fun parseProposal(o: JSONObject, target: String): Proposal {
    val op = o.getString("operation")
    require(op in setOf("create", "update", "pause")) { "草稿操作无效" }
    val draft = o.getJSONObject("draft")
    require(draft.getString("user_id") == target) { "草稿不属于当前计划对象" }
    require(draft.getString("medicine_name").isNotBlank() && draft.getString("unit").isNotBlank() &&
        draft.getDouble("amount").isFinite() && draft.getDouble("amount") > 0) { "草稿药名、用量或单位不完整" }
    require(draft.getString("frequency") in setOf("daily", "weekly", "interval", "as_needed")) { "草稿频率无效" }
    require(draft.getString("frequency") == "as_needed" || draft.getJSONArray("times").length() > 0) { "草稿缺少时间" }
    java.time.LocalDate.parse(draft.getString("start_date"))
    val planId = o.nullable("plan_id")
    require(op == "create" || planId != null) { "草稿缺少原计划编号" }
    return Proposal(o.nullable("draft_id") ?: UUID.randomUUID().toString(), op, planId, draft)
}

@Composable fun ChatScreen(vm: AppViewModel, state: AppState) {
    var target by remember { mutableStateOf(state.user?.id.orEmpty()) }
    var text by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var proposals by remember { mutableStateOf<List<Proposal>>(emptyList()) }
    var editor by remember { mutableStateOf<Proposal?>(null) }
    var pause by remember { mutableStateOf<Proposal?>(null) }
    var phone by remember { mutableStateOf(true) }
    var stream by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val speak = LocalSpeak.current
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it.take(6000) }
    }
    fun send(intent: String) {
        val prompt = text.trim().ifBlank { if (intent == "draft") "请根据刚才已明确的信息生成待确认草稿，缺少信息请追问。" else "" }
        if (running || prompt.isBlank()) return
        text = ""; error = null; partial = ""; running = true
        messages = (messages + ChatMessage("user", prompt.take(6000))).takeLast(19)
        val history = messages
        stream = scope.launch {
            try {
                AiAgent.runChat(json("user_id" to target, "intent" to intent,
                    "messages" to JSONArray(history.map { json("role" to it.role, "content" to it.content.take(6000)) })), state.groupId, phone) { event, data ->
                    withContext(Dispatchers.Main) {
                        when (event) {
                            "delta" -> partial += data.toString()
                            "draft" -> try {
                                val proposal = parseProposal(data as JSONObject, target)
                                if (proposals.none { it.id == proposal.id }) proposals = proposals + proposal
                            } catch (e: Exception) { error = e.message ?: "草稿不完整，未加入列表" }
                            "draft_error" -> error = (data as? JSONObject)?.optString("message")
                        }
                    }
                }
            } catch (e: CancellationException) { error = "已取消，草稿尚未保存"; throw e }
            catch (e: Exception) { error = e.message ?: "对话失败，请重试" }
            finally {
                if (partial.isNotBlank()) messages = (messages + ChatMessage("assistant", partial.take(6000))).takeLast(20)
                partial = ""; running = false
            }
        }
    }
    Page("AI 计划记录助手") {
        Panel {
            Text("只整理已有医嘱，不提供用药建议。所有计划必须由你核对卡片后保存。", color = Red)
            Picker("计划对象", target, state.members.map { it.id to it.username }, {
                target = it; messages = emptyList(); proposals = emptyList(); partial = ""
            }, enabled = !running)
            Check("手机直接连接模型（设备加密凭据）", phone, { phone = it }, enabled = !running)
            if (!phone) Text("使用家药箱服务端 SSE，不会加载网页。")
        }
        messages.forEach { msg -> Panel(if (msg.role == "user") "你" else "记录助手") {
            Text(msg.content)
            if (msg.role == "assistant") Action("朗读", secondary = true) { speak(msg.content) }
        } }
        if (partial.isNotEmpty()) Panel("助手正在回复") { Text(partial) }
        error?.let { Text(it, color = Red) }
        Field("告诉助手：谁、什么药、用量、时间", text, { text = it.take(6000) }, multiline = true, enabled = !running)
        Action("中文语音输入", secondary = true, enabled = !running) {
            runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN").putExtra(RecognizerIntent.EXTRA_PROMPT, "请说药名、用量和服药时间")) }
                .onFailure { error = "手机未安装语音识别服务，请使用文字输入" }
        }
        listOf("请先列出我的已有计划", "请帮我查找家里的药品", "请问还缺少什么信息？").forEach { reply ->
            Action(reply, secondary = true, enabled = !running) { text = reply }
        }
        Action("发送", enabled = !running && target.isNotBlank() && text.isNotBlank()) { send("chat") }
        Action("生成待确认草稿", secondary = true, enabled = !running && target.isNotBlank() && (text.isNotBlank() || messages.isNotEmpty())) { send("draft") }
        if (running) Action("停止回复", secondary = true) { stream?.cancel() }
        Text("待确认的计划（${proposals.size}）", style = MaterialTheme.typography.titleMedium)
        proposals.forEach { p ->
            Panel(when (p.operation) { "pause" -> "待确认暂停"; "update" -> "待确认修改"; else -> "待确认新增" }, warning = true) {
                Text(p.draft.getString("medicine_name"), style = MaterialTheme.typography.titleMedium)
                Text("${p.draft.get("amount")} ${p.draft.getString("unit")} · ${p.draft.getJSONArray("times").strings().joinToString(" / ")}")
                Text("尚未保存。请核对医嘱、对象、日期、用量与优先药盒。")
                Action("打开核对", enabled = !running && !state.busy) { if (p.operation == "pause") pause = p else editor = p }
                Action("丢弃草稿", secondary = true, enabled = !running) { proposals = proposals.filterNot { it.id == p.id } }
            }
        }
    }
    editor?.let { p -> PlanEditor(state, p.draft, { editor = null }) { body ->
        vm.save(if (p.operation == "create") "POST" else "PUT", "/plans" + (p.planId?.let { "/$it" } ?: ""), body) {
            proposals = proposals.filterNot { it.id == p.id }; editor = null
        }
    } }
    pause?.let { p -> Confirm("确认暂停正式计划", "暂停 ${p.draft.getString("medicine_name")}？此操作会更新日历与提醒。", { pause = null }) {
        vm.save("DELETE", "/plans/${p.planId}") { proposals = proposals.filterNot { it.id == p.id }; pause = null }
    } }
}
