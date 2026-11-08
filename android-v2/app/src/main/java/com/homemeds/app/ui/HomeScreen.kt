package com.homemeds.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.homemeds.app.*
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun stateLabel(state: String) = when (state) { "taken" -> "已吃"; "skipped" -> "已跳过"; "overdue" -> "已过时，先核实"; else -> "待吃" }

@Composable internal fun ScannedMedicineResult(state: AppState, data: JSONObject, code: String, intake: () -> Unit) {
    val resolved = resolveMedicineCode(data, code, state.products, state.stock)
    val product = resolved.optJSONObject("product")
    val box = resolved.optJSONObject("box")
    val speak = LocalSpeak.current
    Text("识别类型：${parseCode(code).kind}", color = Muted, style = MaterialTheme.typography.bodySmall)
    if (product != null) {
        Text(product.getString("name"), style = MaterialTheme.typography.titleMedium)
        listOf("specification" to "规格", "manufacturer" to "厂家", "indications" to "适应症", "precautions" to "注意事项").forEach { (key, label) ->
            product.nullable(key)?.let { Text("$label：$it", style = MaterialTheme.typography.bodySmall) }
        }
        if (box == null) state.stock.filter { it.productId == product.getString("id") && it.available }.forEach {
            Text(boxLabel(it, state.today), color = if (it.expiry?.let { d -> d < state.today } == true) Red else Ink,
                style = MaterialTheme.typography.bodySmall)
        }
    } else if (box != null) Text("已识别已入库药盒，药品资料暂未加载，请刷新后查看。")
    else Text("尚未关联药品，可手动录入，或让 AI 检索后逐项核对。")
    if (box != null) {
        Text("已匹配此追溯码对应的已入库药盒，请勿重复添加。", color = Muted)
        listOf("trace_code" to "追溯码", "expiry_date" to "有效期", "batch_number" to "批号",
            "units_remaining" to "剩余数量", "warehouse_name" to "药箱").forEach { (key, label) ->
            box.nullable(key)?.let { Text("$label：$it", style = MaterialTheme.typography.bodySmall) }
        }
        val expiry = box.nullable("expiry_date")?.let { runCatching { medicineExpiry(it)?.date?.toString() }.getOrNull() }
        if (expiry != null && expiry < state.today) Text("此药盒已过期，请勿自行服用。", color = Red)
        Text("药盒状态：" + when (box.optString("status")) { "empty" -> "用尽"; "discarded" -> "报废"; "active" -> "在库"; else -> "待刷新确认" })
    }
    ActionRow {
        if (product != null) Action("朗读资料", secondary = true) { speak("${product.getString("name")}。${product.nullable("indications").orEmpty()}。${product.nullable("precautions").orEmpty()}") }
        Action("进入入库向导", enabled = box == null && !state.busy, onClick = intake)
    }
}

@Composable internal fun MedicineLookupEntry(enabled: Boolean, scan: () -> Unit, photograph: () -> Unit) {
    var choose by remember { mutableStateOf(false) }
    Action("扫码查药 / 入库", enabled = enabled) { choose = true }
    if (choose) Modal("选择查药方式", { choose = false }) {
        Text("有条码可扫码；也可以拍摄同一药盒的多个角度查询资料，再核对入库。", style = MaterialTheme.typography.bodySmall)
        ActionRow {
            Action("扫描条码 / 追溯码") { choose = false; scan() }
            Action("拍照查药 / 入库（多角度）", secondary = true) { choose = false; photograph() }
        }
    }
}

internal fun todoColor(state: String) = when (state) { "taken" -> Green; "skipped" -> Muted; "overdue" -> Red; else -> Amber }

/** One to-do card: time + status, one medicine line, then the primary action with less-used ones in "⋮". */
@Composable internal fun TodoCard(todo: Todo, busy: Boolean, record: () -> Unit, skip: () -> Unit, undo: (DoseLog) -> Unit, speak: () -> Unit) {
    Panel(warning = todo.state == "overdue") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(todo.time, style = MaterialTheme.typography.titleMedium)
            StatusChip(stateLabel(todo.state), todoColor(todo.state))
        }
        Text(listOf(todo.plan.medicineName, "${Texts.amount(todo.plan.amount)} ${todo.plan.unit}", Texts.meal(todo.plan.mealRelation))
            .filter(String::isNotBlank).joinToString(" · "), fontWeight = FontWeight.SemiBold)
        if (todo.plan.notes.isNotBlank()) Text(todo.plan.notes, color = Muted, style = MaterialTheme.typography.bodySmall)
        if (todo.state in setOf("taken", "skipped")) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记录人：${todo.log?.data?.optString("actor_name").orEmpty()}，请勿重复服药。", Modifier.weight(1f),
                color = Muted, style = MaterialTheme.typography.bodySmall)
            todo.log?.let { log -> TextButton({ undo(log) }, Modifier.heightIn(min = touchHeight), enabled = !busy) { Text("撤销") } }
        } else Row(Modifier.testTag("todo_actions"), verticalAlignment = Alignment.CenterVertically) {
            Action("核对并记录服药", enabled = !busy, modifier = Modifier.weight(1f), onClick = record)
            MoreMenu(listOf(MenuAction("这次跳过", enabled = !busy, onClick = skip), MenuAction("朗读", onClick = speak)))
        }
    }
}

@Composable fun HomeScreen(vm: AppViewModel, state: AppState, dose: (DoseRequest) -> Unit) {
    var scan by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }
    var intake by remember { mutableStateOf(false) }
    var intakeDraft by remember { mutableStateOf<JSONObject?>(null) }
    var photograph by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<PhotoAttachment>>(emptyList()) }
    var photoResult by remember { mutableStateOf(false) }
    var member by remember { mutableStateOf(state.user?.id) }
    var skip by remember { mutableStateOf<Todo?>(null) }
    var expiryList by remember { mutableStateOf(false) }
    val speak = LocalSpeak.current
    val active = state.stock.filter { it.available }
    val expiredBoxes = active.filter { it.expiry?.let { d -> d < state.today } == true }
    val soonBoxes = active.filter { it.expiry?.let { d -> ChronoUnit.DAYS.between(LocalDate.parse(state.today), LocalDate.parse(d)) in 0..30 } == true }
    Page(disclaimer = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(state.today, fontWeight = FontWeight.SemiBold)
                Text(state.timezone, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            MedicineLookupEntry(state.groupId != null && !state.busy, scan = { scan = true }, photograph = {
                photos = emptyList(); photograph = true
            })
        }
        if (state.groupId == null) Panel { Text("请先在设置中创建或选择家庭。") }
        if (expiredBoxes.size + soonBoxes.size > 0) Surface({ expiryList = true }, Modifier.fillMaxWidth().heightIn(min = touchHeight),
            shape = RoundedCornerShape(12.dp), color = Color(0xFFFFF4D6), border = BorderStroke(1.dp, Amber)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, null, tint = Red)
                Text("已过期 ${expiredBoxes.size} 盒 · 30 天内到期 ${soonBoxes.size} 盒", Modifier.weight(1f), color = Red)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看到期药盒", tint = Red)
            }
        }
        val shown = state.todos.filter { it.plan.userId == member }
        if (state.members.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.members.forEach { user ->
                val todos = state.todos.filter { it.plan.userId == user.id }
                FilterChip(member == user.id, { member = user.id },
                    label = { Text("${user.username} ${todos.count { it.state == "taken" || it.state == "skipped" }}/${todos.size}") },
                    modifier = Modifier.heightIn(min = touchHeight))
            }
        }
        Text("已记录 ${shown.count { it.state in setOf("taken", "skipped") }} / ${shown.size}", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (shown.isEmpty()) Panel { Text("今天没有定时服药项。按需服药请到吃药计划中记录。") }
        shown.forEach { todo ->
            TodoCard(todo, state.busy, record = { dose(DoseRequest(todo.plan, todo.date, todo.time)) }, skip = { skip = todo },
                undo = { log -> vm.save("POST", "/doses/${log.id}/undo") },
                speak = { speak("${todo.time}，${todo.plan.medicineName}，${Texts.amount(todo.plan.amount)}${todo.plan.unit}，${Texts.meal(todo.plan.mealRelation)}。${stateLabel(todo.state)}") })
        }
    }
    if (expiryList) Modal("请检查家庭药箱", { expiryList = false }) {
        Text("请先核对实物。过期药不要自行服用。", color = Red)
        if (expiredBoxes.isNotEmpty()) Text("已过期 ${expiredBoxes.size} 盒", style = MaterialTheme.typography.titleMedium)
        expiredBoxes.forEach { Text("${it.name}：${boxLabel(it, state.today)}", color = Red, style = MaterialTheme.typography.bodySmall) }
        if (soonBoxes.isNotEmpty()) Text("30 天内到期 ${soonBoxes.size} 盒", style = MaterialTheme.typography.titleMedium)
        soonBoxes.forEach { Text("${it.name}：${boxLabel(it, state.today)}", style = MaterialTheme.typography.bodySmall) }
    }
    if (scan) ScannerDialog({ scan = false }) { raw ->
        scan = false; code = raw
        vm.perform { result = vm.get("/codes/resolve?code=" + URLEncoder.encode(raw, "UTF-8")) }
    }
    result?.let { data ->
        Modal("扫码结果", { result = null }) {
            ScannedMedicineResult(state, data, code) { result = null; intakeDraft = null; intake = true }
        }
    }
    if (photograph) PhotoCaptureDialog({ photograph = false; photos = emptyList() }, initialPhotos = photos) {
        photos = it; photograph = false; photoResult = true
    }
    if (photoResult) PhotoLookupDialog(state, photos, dismiss = { photoResult = false; photos = emptyList() },
        revise = { photoResult = false; photograph = true }, intake = { draft ->
            intakeDraft = draft; code = ""; photoResult = false; photos = emptyList(); intake = true
        })
    if (intake) IntakeDialog(vm, state, initialCode = code, initialDraft = intakeDraft) { intake = false }
    skip?.let { todo -> Confirm("确认跳过本次", "${todo.time} ${todo.plan.medicineName}。只记录跳过，不扣库存。", { skip = null }) {
        vm.save("POST", "/doses", json("plan_id" to todo.plan.id, "local_date" to todo.date, "scheduled_time" to todo.time, "status" to "skipped")) { skip = null }
    } }
}
