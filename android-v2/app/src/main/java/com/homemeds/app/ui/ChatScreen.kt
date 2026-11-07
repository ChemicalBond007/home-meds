package com.homemeds.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homemeds.app.*
import com.homemeds.app.net.AiAgent
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(val role: String, val content: String, val imageDataUrls: List<String> = emptyList()) {
    override fun toString() = "ChatMessage($role)"
}
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

@Composable fun ChatScreen(vm: AppViewModel, state: AppState, clearRequested: Boolean = false, clearHandled: () -> Unit = {}) {
    val target = assistantTarget(state)
    key(state.user?.id, state.groupId, target, state.assistantPhone) {
        ClearableChat(clearRequested, clearHandled) { ChatConversation(vm, state, target) }
    }
}

@Composable internal fun ClearableChat(clearRequested: Boolean, clearHandled: () -> Unit, conversation: @Composable () -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    // Replacing the whole session also cancels its stream and drops image/history context.
    key(generation) { conversation() }
    if (clearRequested) AlertDialog(
        onDismissRequest = clearHandled,
        title = { Text("清空对话历史") },
        text = { Text("将停止正在进行的回复，清空当前对话、推荐回复、输入内容、图片附件及未保存草稿。已保存的药品、库存和计划不受影响。此操作无法撤销。") },
        dismissButton = { TextButton(clearHandled) { Text("取消") } },
        confirmButton = { TextButton({ generation++; clearHandled() }) { Text("确认清空") } },
    )
}

@Composable private fun ChatConversation(vm: AppViewModel, state: AppState, target: String?) {
    var text by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var proposals by remember { mutableStateOf<List<Proposal>>(emptyList()) }
    var medicineDrafts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var intake by remember { mutableStateOf<JSONObject?>(null) }
    var photoDialog by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<PhotoAttachment>>(emptyList()) }
    var editor by remember { mutableStateOf<Proposal?>(null) }
    var pause by remember { mutableStateOf<Proposal?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var stream by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val speak = LocalSpeak.current
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it.take(6000) }
    }
    fun send() {
        val prompt = text.trim().ifBlank {
            if (photos.isNotEmpty()) "请综合这些照片中同一药盒的各个角度识别药品并生成待确认入库草稿"
            else ""
        }
        if (running || target == null || state.groupId == null || prompt.isBlank()) return
        text = ""; error = null; partial = ""; suggestions = emptyList(); running = true
        messages = (messages + ChatMessage("user", prompt.take(6000), photos.map { it.dataUrl })).takeLast(19)
        photos = emptyList()
        val history = messages
        stream = scope.launch {
            try {
                AiAgent.runChat(json("user_id" to target, "intent" to "chat",
                    "messages" to JSONArray(history.map {
                        if (it.role == "user") AiAgent.userMessage(it.content.take(6000), it.imageDataUrls)
                        else json("role" to it.role, "content" to it.content.take(6000))
                    })), state.groupId, state.assistantPhone) { event, data ->
                    withContext(Dispatchers.Main) {
                        when (event) {
                            "delta" -> partial += data.toString()
                            "suggestions" -> suggestions = (data as JSONArray).strings()
                            "draft" -> try {
                                val proposal = parseProposal(data as JSONObject, target)
                                if (proposals.none { it.id == proposal.id }) proposals = proposals + proposal
                            } catch (e: Exception) { error = e.message ?: "草稿不完整，未加入列表" }
                            "draft_error" -> error = (data as? JSONObject)?.optString("message")
                            "medicine_draft" -> {
                                val draft = data as JSONObject
                                if (medicineDrafts.none { it.optString("draft_id") == draft.getString("draft_id") })
                                    medicineDrafts = medicineDrafts + draft
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { suggestions = emptyList(); error = "已取消，草稿尚未保存"; throw e }
            catch (e: Exception) { suggestions = emptyList(); error = e.message ?: "对话失败，请重试" }
            finally {
                if (partial.isNotBlank()) messages = (messages + ChatMessage("assistant", partial.take(6000))).takeLast(20)
                partial = ""; running = false
            }
        }
    }
    ChatLayout(revision = "${messages.size}:${partial.length}:${medicineDrafts.size}:${proposals.size}", composer = {
        ChatComposer(text, { text = it.take(6000) }, photos, suggestions, running,
            canSend = target != null && state.groupId != null && (text.isNotBlank() || photos.isNotEmpty()),
            photograph = { photoDialog = true },
            removePhoto = { index -> photos = photos.filterIndexed { i, _ -> i != index } },
            voice = {
                runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN").putExtra(RecognizerIntent.EXTRA_PROMPT, "请说药名、用量和服药时间")) }
                    .onFailure { error = "手机未安装语音识别服务，请使用文字输入" }
            }, send = ::send, stop = { stream?.cancel() })
    }) {
        if (target == null) item { Text("请在设置 → 助手偏好中选择计划对象。", color = Muted) }
        if (messages.isEmpty() && partial.isEmpty()) item {
            Text("可以发送文字或拍摄药盒的多个角度，助手会在信息充分时生成待确认草稿。", color = Muted)
        }
        items(messages) { msg -> Panel(if (msg.role == "user") "你" else "AI助手") {
            Text(msg.content)
            if (msg.imageDataUrls.isNotEmpty()) Text("[已附 ${msg.imageDataUrls.size} 张药品图片]", color = Muted)
            if (msg.role == "assistant") Action("朗读", secondary = true) { speak(msg.content) }
        } }
        if (partial.isNotEmpty()) item { Panel("助手正在回复") { Text(partial) } }
        error?.let { message -> item { Text(message, color = Muted) } }
        if (medicineDrafts.isNotEmpty()) item { Text("待确认的药品（${medicineDrafts.size}）", style = MaterialTheme.typography.titleMedium) }
        items(medicineDrafts) { draft ->
            Panel(draft.optString("name"), warning = true) {
                listOf("specification" to "规格", "manufacturer" to "厂家", "units_per_package" to "每盒数量",
                    "expiry_date" to "有效期", "batch_number" to "批号", "code" to "条码 / 追溯码").forEach { (key, label) ->
                    if (!draft.isNull(key)) Text("$label：${draft.get(key)}")
                }
                Text("尚未入库，请逐项核对实物。")
                Sources(parseSources(draft.opt("sources")))
                Action("打开核对并入库", enabled = !running && !state.busy) { intake = draft }
                Action("丢弃草稿", secondary = true, enabled = !running) {
                    medicineDrafts = medicineDrafts.filterNot { it.optString("draft_id") == draft.optString("draft_id") }
                }
            }
        }
        if (proposals.isNotEmpty()) item { Text("待确认的计划（${proposals.size}）", style = MaterialTheme.typography.titleMedium) }
        items(proposals) { p ->
            Panel(when (p.operation) { "pause" -> "待确认暂停"; "update" -> "待确认修改"; else -> "待确认新增" }, warning = true) {
                Text(p.draft.getString("medicine_name"), style = MaterialTheme.typography.titleMedium)
                Text("${p.draft.get("amount")} ${p.draft.getString("unit")} · ${p.draft.getJSONArray("times").strings().joinToString(" / ")}")
                Text("尚未保存。请核对医嘱、对象、日期、用量与优先药盒。")
                Action("打开核对", enabled = !running && !state.busy) { if (p.operation == "pause") pause = p else editor = p }
                Action("丢弃草稿", secondary = true, enabled = !running) { proposals = proposals.filterNot { it.id == p.id } }
            }
        }
    }
    if (photoDialog) PhotoCaptureDialog({ photoDialog = false }, initialPhotos = photos) { photos = it; photoDialog = false }
    intake?.let { draft ->
        IntakeDialog(vm, state, initialDraft = draft, onSaved = {
            medicineDrafts = medicineDrafts.filterNot { it.optString("draft_id") == draft.optString("draft_id") }
        }) { intake = null }
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

@Composable internal fun ChatLayout(revision: Any, composer: @Composable ColumnScope.() -> Unit, content: LazyListScope.() -> Unit) {
    val scroll = rememberLazyListState()
    LaunchedEffect(revision) {
        val count = scroll.layoutInfo.totalItemsCount
        if (count > 0 && (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= count - 2)
            scroll.scrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_history"), state = scroll,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        Column(Modifier.fillMaxWidth(), content = composer)
    }
}

@Composable internal fun ChatComposer(
    text: String, change: (String) -> Unit, photos: List<PhotoAttachment>, suggestions: List<String>,
    running: Boolean, canSend: Boolean, photograph: () -> Unit, removePhoto: (Int) -> Unit,
    voice: () -> Unit, send: () -> Unit, stop: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("chat_composer"),
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        BoxWithConstraints {
            val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
            val expandedHeight = lineHeight * 2 + 24.dp + touchHeight + 8.dp +
                (if (photos.isNotEmpty()) 96.dp else 0.dp) + (if (suggestions.isNotEmpty()) touchHeight else 0.dp)
            val compact = maxHeight < expandedHeight
            Column {
                if (suggestions.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().testTag("reply_suggestions"),
                    contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { reply ->
                        SuggestionChip(onClick = { change(reply) }, enabled = !running,
                            label = { Text(reply, Modifier.widthIn(max = 260.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
                // Collapse previews, not attachments, when the keyboard leaves little room.
                if (photos.isNotEmpty() && !compact) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos.size) { index ->
                        Box {
                            Image(photos[index].preview.asImageBitmap(), "药品照片 ${index + 1}", Modifier.size(80.dp))
                            IconButton({ removePhoto(index) }, Modifier.align(Alignment.TopEnd), enabled = !running) {
                                Icon(Icons.Default.Close, "移除照片 ${index + 1}")
                            }
                        }
                    }
                }
                BasicTextField(text, change, enabled = !running,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("chat_input"),
                    minLines = if (compact) 1 else 2, maxLines = if (compact) 2 else 4, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (!running && canSend) send() }),
                    decorationBox = { field ->
                        Box {
                            if (text.isEmpty()) Text("输入消息，或拍照识别药品", color = Muted)
                            field()
                        }
                    })
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(photograph, Modifier.size(touchHeight), enabled = !running) { Icon(Icons.Default.PhotoCamera, "拍照或选择照片") }
                    if (photos.isNotEmpty()) Text("${photos.size} / $MAX_MEDICINE_PHOTOS", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    IconButton(voice, Modifier.size(touchHeight), enabled = !running) { Icon(Icons.Default.Mic, "语音输入") }
                    if (running) FilledIconButton(stop, Modifier.size(touchHeight)) { Icon(Icons.Default.Stop, "停止回复") }
                    else FilledIconButton(send, Modifier.size(touchHeight), enabled = canSend) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                }
            }
        }
    }
}
