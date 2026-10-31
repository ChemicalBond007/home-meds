package com.homemeds.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.homemeds.app.*
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun stateLabel(state: String) = when (state) { "taken" -> "已吃"; "skipped" -> "已跳过"; "overdue" -> "已过时，先核实"; else -> "待吃" }

@Composable fun HomeScreen(vm: AppViewModel, state: AppState, dose: (DoseRequest) -> Unit) {
    var scan by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }
    var intake by remember { mutableStateOf(false) }
    var member by remember { mutableStateOf(state.user?.id) }
    var skip by remember { mutableStateOf<Todo?>(null) }
    val speak = LocalSpeak.current
    val active = state.stock.filter { it.available }
    val expired = active.count { it.expiry?.let { d -> d < state.today } == true }
    val soon = active.count { it.expiry?.let { d -> ChronoUnit.DAYS.between(LocalDate.parse(state.today), LocalDate.parse(d)) in 0..30 } == true }
    Page("今日服药") {
        Text("${state.today} · ${state.timezone}", color = Muted)
        Action("扫码查药 / 入库", enabled = state.groupId != null && !state.busy) { scan = true }
        if (state.groupId == null) Panel { Text("请先在设置中创建或选择家庭。") }
        if (expired + soon > 0) Panel("请检查家庭药箱", warning = true) {
            Text("已过期 $expired 盒 · 30 天内到期 $soon 盒", color = Red)
            Text("请先核对实物。过期药不要自行服用。")
        }
        Panel("家人今日进度") {
            state.members.forEach { user ->
                val todos = state.todos.filter { it.plan.userId == user.id }
                Action("${user.username} · ${todos.count { it.state == "taken" || it.state == "skipped" }}/${todos.size}",
                    secondary = member != user.id) { member = user.id }
            }
        }
        val shown = state.todos.filter { it.plan.userId == member }
        Text("已记录 ${shown.count { it.state in setOf("taken", "skipped") }} / ${shown.size}", color = Muted)
        if (shown.isEmpty()) Panel { Text("今天没有定时服药项。按需服药请到吃药计划中记录。") }
        shown.forEach { todo ->
            Panel("${todo.time} · ${stateLabel(todo.state)}", warning = todo.state == "overdue") {
                Text(todo.plan.medicineName, style = MaterialTheme.typography.titleMedium)
                Text("${Texts.amount(todo.plan.amount)} ${todo.plan.unit} · ${Texts.meal(todo.plan.mealRelation)}")
                if (todo.plan.notes.isNotBlank()) Text(todo.plan.notes)
                Action("朗读", secondary = true) { speak("${todo.time}，${todo.plan.medicineName}，${Texts.amount(todo.plan.amount)}${todo.plan.unit}，${Texts.meal(todo.plan.mealRelation)}。${stateLabel(todo.state)}") }
                if (todo.state in setOf("taken", "skipped")) {
                    Text("记录人：${todo.log?.data?.optString("actor_name").orEmpty()}，请勿重复服药。")
                    todo.log?.let { log -> Action("撤销这条记录", secondary = true, enabled = !state.busy) {
                        vm.save("POST", "/doses/${log.id}/undo")
                    } }
                } else {
                    Action("核对并记录服药", enabled = !state.busy) { dose(DoseRequest(todo.plan, todo.date, todo.time)) }
                    Action("这次跳过", secondary = true, enabled = !state.busy) { skip = todo }
                }
            }
        }
        Action("刷新今日清单", secondary = true, enabled = !state.busy) { vm.refresh() }
    }
    if (scan) ScannerDialog({ scan = false }) { raw ->
        scan = false; code = raw
        vm.perform { result = vm.get("/codes/resolve?code=" + URLEncoder.encode(raw, "UTF-8")) }
    }
    result?.let { data ->
        Modal("扫码结果", { result = null }) {
            val product = data.optJSONObject("product")
            val box = data.optJSONObject("box")
            Text("识别类型：${parseCode(code).kind}")
            if (product != null) {
                Text(product.getString("name"), style = MaterialTheme.typography.titleLarge)
                listOf("specification" to "规格", "manufacturer" to "厂家", "indications" to "适应症", "precautions" to "注意事项").forEach { (key, label) ->
                    product.nullable(key)?.let { Text("$label：$it") }
                }
                state.stock.filter { it.productId == product.getString("id") && it.available }.forEach {
                    Text(boxLabel(it, state.today), color = if (it.expiry?.let { d -> d < state.today } == true) Red else Ink)
                }
                if (box != null) Text("该追溯码药盒已入库，请勿重复添加。", color = Red)
                Action("朗读资料", secondary = true) { speak("${product.getString("name")}。${product.nullable("indications").orEmpty()}。${product.nullable("precautions").orEmpty()}") }
            } else Text("尚未关联药品，可手动录入，或让 AI 检索后逐项核对。")
            Action("进入入库向导", enabled = box == null) { result = null; intake = true }
        }
    }
    if (intake) IntakeDialog(vm, state, code) { intake = false }
    skip?.let { todo -> Confirm("确认跳过本次", "${todo.time} ${todo.plan.medicineName}。只记录跳过，不扣库存。", { skip = null }) {
        vm.save("POST", "/doses", json("plan_id" to todo.plan.id, "local_date" to todo.date, "scheduled_time" to todo.time, "status" to "skipped")) { skip = null }
    } }
}
