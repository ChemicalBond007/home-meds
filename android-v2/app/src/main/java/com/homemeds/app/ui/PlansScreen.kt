package com.homemeds.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.homemeds.app.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Composable fun PlansScreen(vm: AppViewModel, state: AppState, dose: (DoseRequest) -> Unit) {
    var editor by remember { mutableStateOf<JSONObject?>(null) }
    var editId by remember { mutableStateOf<String?>(null) }
    var pause by remember { mutableStateOf<Plan?>(null) }
    Page("吃药计划") {
        Action("新建吃药计划", enabled = state.groupId != null && state.members.isNotEmpty() && !state.busy) { editId = null; editor = JSONObject() }
        state.members.forEach { member ->
            val plans = state.plans.filter { it.userId == member.id }
            if (plans.isNotEmpty()) Text(member.username, style = MaterialTheme.typography.titleMedium)
            plans.forEach { p ->
                Panel(p.medicineName) {
                    Text("${Texts.amount(p.amount)} ${p.unit} · ${Texts.meal(p.mealRelation)}")
                    Text(if (p.frequency == "as_needed") "按需服用" else p.times.joinToString(" / "))
                    Text("${p.startDate} 起 · ${if (p.enabled) "已启用" else "已暂停"}")
                    if (p.notes.isNotBlank()) Text(p.notes)
                    p.preferredStockItemId?.let { id -> Text("优先药盒：" + (state.stock.find { it.id == id }?.let { boxLabel(it, state.today) } ?: "已不可用，请核对")) }
                    Action("编辑计划", secondary = true, enabled = !state.busy) { editId = p.id; editor = p.body() }
                    if (p.enabled) Action("暂停计划", secondary = true, enabled = !state.busy) { pause = p }
                    if (p.enabled && p.frequency == "as_needed" && state.today >= p.startDate.toString() &&
                        (p.endDate == null || state.today <= p.endDate.toString())) Action("记录一次按需服药", enabled = !state.busy) {
                        dose(DoseRequest(p, state.today, "as-needed:${UUID.randomUUID()}"))
                    }
                }
            }
        }
        if (state.plans.isEmpty()) Panel { Text("还没有服药计划。请按医嘱记录，或使用 AI 助手整理待确认草稿。") }
    }
    editor?.let { initial ->
        PlanEditor(state, initial, { editor = null }) { body ->
            vm.save(if (editId == null) "POST" else "PUT", "/plans" + (editId?.let { "/$it" } ?: ""), body) { editor = null }
        }
    }
    pause?.let { plan -> Confirm("暂停计划", "暂停 ${plan.medicineName}？会自动更新本地日历和提醒。", { pause = null }) {
        vm.save("DELETE", "/plans/${plan.id}") { pause = null }
    } }
}

@Composable fun PlanEditor(state: AppState, initial: JSONObject, dismiss: () -> Unit, save: (JSONObject) -> Unit) {
    var member by remember { mutableStateOf(initial.nullable("user_id") ?: state.user?.id ?: state.members.firstOrNull()?.id.orEmpty()) }
    var productId by remember { mutableStateOf(initial.nullable("product_id").orEmpty()) }
    var preferred by remember { mutableStateOf(initial.nullable("preferred_stock_item_id").orEmpty()) }
    var name by remember { mutableStateOf(initial.optString("medicine_name")) }
    var amount by remember { mutableStateOf(initial.opt("amount")?.toString() ?: "1") }
    var unit by remember { mutableStateOf(initial.optString("unit", "片")) }
    var frequency by remember { mutableStateOf(initial.optString("frequency", "daily")) }
    var times by remember { mutableStateOf(initial.optJSONArray("times")?.strings()?.joinToString(",") ?: "08:00") }
    var weekdays by remember { mutableStateOf(initial.optJSONArray("weekdays")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()) }
    var interval by remember { mutableStateOf(initial.optInt("interval_days", 1).toString()) }
    var start by remember { mutableStateOf(initial.optString("start_date", state.today)) }
    var end by remember { mutableStateOf(initial.nullable("end_date").orEmpty()) }
    var meal by remember { mutableStateOf(initial.optString("meal_relation", "none")) }
    var notes by remember { mutableStateOf(initial.optString("notes")) }
    var enabled by remember { mutableStateOf(!initial.has("enabled") || initial.optBoolean("enabled", initial.optInt("enabled", 1) == 1)) }
    var notify by remember { mutableStateOf(initial.optBoolean("notify_family", state.members.find { it.id == member }?.elderly == true)) }
    var error by remember { mutableStateOf<String?>(null) }
    val boxes = availableBoxes(state.stock, productId.takeIf(String::isNotEmpty))
    Modal("核对吃药计划", dismiss) {
        Text("只记录医嘱或说明书明确的安排，不会自动推荐药物或用量。", color = Muted)
        Picker("计划对象", member, state.members.map { it.id to it.username }, { member = it })
        Picker("药品", productId, listOf("" to "手动填写药名（不扣库存）") + state.products.map { it.id to it.name }, {
            productId = it; preferred = ""
            state.products.find { p -> p.id == it }?.let { p -> name = p.name; unit = p.unit }
        })
        Field("药名", name, { name = it }, enabled = productId.isEmpty())
        Field("每次用量", amount, { amount = it }, number = true)
        Field("用量单位", unit, { unit = it }, enabled = productId.isEmpty())
        Picker("频率", frequency, listOf("daily" to "每天", "weekly" to "每周", "interval" to "每隔几天", "as_needed" to "按需服药"), { frequency = it })
        if (frequency != "as_needed") {
            Field("服药时间 HH:mm，多个用逗号分隔", times, { times = it })
            state.groups.find { it.id == state.groupId }?.mealTimes?.let { meals ->
                listOf("morning" to "早餐", "noon" to "午餐", "evening" to "晚餐", "bedtime" to "睡前").forEach { (key, label) ->
                    meals.nullable(key)?.let { t -> Action("$label $t", secondary = true) {
                        times = (times.split(',', '，').map(String::trim).filter(String::isNotBlank) + t).distinct().sorted().joinToString(",")
                    } }
                }
            }
        }
        if (frequency == "weekly") listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六").forEachIndexed { i, day ->
            Check(day, i in weekdays, { weekdays = if (it) weekdays + i else weekdays - i })
        }
        if (frequency == "interval") Field("间隔天数（1–365）", interval, { interval = it }, number = true)
        Field("开始日期 YYYY-MM-DD", start, { start = it })
        Field("结束日期（可留空）", end, { end = it })
        Picker("餐时关系", meal, listOf("none" to "不限", "before" to "饭前", "after" to "饭后", "with" to "随餐"), { meal = it })
        if (productId.isNotEmpty()) Picker("优先药盒（请核对有效期）", preferred,
            listOf("" to "不指定，下次手动核对") + boxes.map { it.id to boxLabel(it, state.today) } +
                if (preferred.isNotBlank() && boxes.none { it.id == preferred }) listOf(preferred to "原优先药盒不可用（保留原偏好）") else emptyList(),
            { preferred = it })
        Field("备注", notes, { notes = it }, multiline = true)
        Check("启用计划", enabled, { enabled = it })
        Check("超过 30 分钟未记录时提醒家人", notify, { notify = it })
        error?.let { Text(it, color = Red) }
        Action("确认保存", enabled = !state.busy) {
            try {
                require(member.isNotBlank() && state.members.any { it.id == member }) { "请选择家庭成员" }
                require(name.isNotBlank() && unit.isNotBlank()) { "请填写药名与单位" }
                val n = amount.toDoubleOrNull()
                require(n != null && n.isFinite() && n in .001..100000.0) { "用量应为 0.001–100000" }
                val ts = if (frequency == "as_needed") emptyList() else times.split(',', '，', ' ').map(String::trim).filter(String::isNotEmpty).distinct().sorted()
                require(frequency == "as_needed" || ts.isNotEmpty()) { "请填写服药时间" }
                ts.forEach { require(Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(it)) { "服药时间须为 HH:mm" }; LocalTime.parse(it) }
                LocalDate.parse(start)
                if (end.isNotBlank()) require(LocalDate.parse(end) >= LocalDate.parse(start)) { "结束日期不能早于开始日期" }
                require(frequency != "weekly" || weekdays.isNotEmpty()) { "请选择每周几" }
                val days = interval.toIntOrNull() ?: 0
                require(frequency != "interval" || days in 1..365) { "间隔天数应为 1–365" }
                save(json("user_id" to member, "product_id" to productId.ifBlank { null }, "preferred_stock_item_id" to preferred.ifBlank { null },
                    "medicine_name" to name.trim(), "amount" to n, "unit" to unit.trim(), "times" to JSONArray(ts),
                    "frequency" to frequency, "weekdays" to JSONArray(weekdays.sorted()), "interval_days" to if (frequency == "interval") days else 1,
                    "start_date" to start, "end_date" to end.ifBlank { null }, "meal_relation" to meal, "notes" to notes,
                    "enabled" to enabled, "notify_family" to notify))
            } catch (e: Exception) { error = e.message ?: "请核对计划资料" }
        }
    }
}
