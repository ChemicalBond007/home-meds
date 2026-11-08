package com.homemeds.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.homemeds.app.*
import com.homemeds.app.net.ApiException
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.time.*
import org.json.JSONObject

@Composable fun DoseDialog(vm: AppViewModel, state: AppState, request: DoseRequest, dismiss: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    var amount by remember { mutableStateOf(Texts.amount(request.plan.amount)) }
    val zone = remember(state.timezone) { ZoneId.of(state.timezone) }
    var time by remember { mutableStateOf(LocalTime.now(zone).withSecond(0).withNano(0).toString()) }
    var boxes by remember { mutableStateOf<PlanBoxes?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf(false) }
    var expired by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(request.plan.id, reload) {
        boxes = null; selected = null; verified = false
        try {
            val data = PlanBoxes.parse(vm.get("/plans/${request.plan.id}/boxes"))
            boxes = data; selected = initialDoseBox(data)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "药盒读取失败，请重试后再核对" }
    }
    fun submit(confirmExpired: Boolean) {
        vm.perform {
            try {
                val box = boxes?.boxes?.find { it.id == selected }
                val taken = LocalDate.parse(request.date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()
                val body = json("plan_id" to request.plan.id, "local_date" to request.date,
                    "scheduled_time" to request.time, "status" to "taken", "amount" to amount.toDouble(),
                    "taken_at" to taken.toString(), "stock_item_id" to selected?.takeIf { it.isNotEmpty() }, "confirm_expired" to confirmExpired)
                box?.let { body.put("expected_stock_version", it.version) }
                vm.mutate("POST", "/doses", body)
                dismiss()
            } catch (e: ApiException) {
                if (e.status == 409) { error = e.message; expired = false; reload++; verified = false }
                throw e
            }
        }
    }
    Modal(if (step == 1) "第 1 步：核对时间和用量" else "第 2 步：核对药盒实物", dismiss) {
        Panel {
            Text(request.plan.medicineName, style = MaterialTheme.typography.titleMedium)
            Text(listOf(request.date, if (request.time.startsWith("as-needed:")) "按需服药" else "计划 ${request.time}",
                "计划用量 ${Texts.amount(request.plan.amount)} ${request.plan.unit}", Texts.meal(request.plan.mealRelation))
                .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (request.plan.notes.isNotBlank()) Text(request.plan.notes, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (step == 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("实际用量（${request.plan.unit}）", amount, { amount = it }, Modifier.weight(1f), number = true)
                Field("时间 HH:mm", time, { time = it }, Modifier.weight(1f))
            }
            Text("只记录已经核对的实际服药，不要根据提醒重复服药。", color = Red, style = MaterialTheme.typography.bodySmall)
            Action("下一步：核对药盒", enabled = !state.busy) {
                val n = amount.toDoubleOrNull()
                val t = runCatching { LocalTime.parse(time) }.getOrNull()
                if (n == null || !n.isFinite() || n <= 0 || n > 100000 || t == null) error = "请填写有效的用量与时间"
                else {
                    val instant = LocalDate.parse(request.date).atTime(t).atZone(zone).toInstant()
                    if (instant > Instant.now().plusSeconds(300) || LocalDate.now(zone).toString() != request.date) error = "只能记录今天，服药时间不能晚于现在"
                    else { error = null; step = 2 }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择本次使用的药盒", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (boxes != null) Action("扫码核对药盒", secondary = true) { scan = true }
            }
            if (boxes == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("正在读取药盒…", Modifier.weight(1f))
                Action("重新读取", secondary = true) { reload++ }
            }
            boxes?.let { data ->
                if (data.preferred != null && data.boxes.none { it.id == data.preferred }) Text("优先药盒已不可用，请重新选择，不能自动换盒。", color = Red)
                data.boxes.forEach { box ->
                    val label = (if (box.id == data.preferred) "优先药盒 · " else if (box.id == data.last) "上次药盒 · " else "") + boxLabel(box, request.date)
                    Check(label, selected == box.id, { selected = box.id; verified = false })
                }
                Check("不扣库存（使用了未入库的药盒）", selected == "", { selected = ""; verified = false })
                Check("我已对照实物核对药名、规格、有效期、用量与药盒", verified, { verified = it })
            }
            ActionRow {
                Action("返回上一步", secondary = true, enabled = !state.busy) { step = 1 }
                boxes?.let { data ->
                    Action("确认已经服药", enabled = !state.busy && canConfirmDoseBox(data, selected, verified)) {
                        val box = data.boxes.find { it.id == selected }
                        if (box?.expiry?.let { it < request.date } == true) expired = true else submit(false)
                    }
                }
            }
        }
        error?.let { Text(it, color = Red) }
    }
    if (scan) ScannerDialog({ scan = false }) { raw ->
        scan = false; verified = false
        vm.perform {
            val resolved = resolveMedicineCode(vm.get("/codes/resolve?code=" + URLEncoder.encode(raw, "UTF-8")), raw, state.products, state.stock)
            val parsed = parseCode(raw)
            val exactBox = resolved.optJSONObject("box")?.nullable("id")
            val product = resolved.optJSONObject("product")?.nullable("id")
            if (exactBox != null && boxes?.boxes?.any { it.id == exactBox } == true) {
                selected = exactBox; error = "已匹配追溯码，请继续对照实物核对并勾选。"
            } else if (parsed.traceCode != null) error = "该追溯码不是此计划的可用药盒，请勿确认。"
            else if (product != null && product == request.plan.productId) error = "商品条码只匹配药品，不能区分药盒。请手动选择并核对实物。"
            else error = "扫码药品不匹配，请重新核对。"
        }
    }
    if (expired) Confirm("过期药再次确认", "所选药盒已过期。不建议使用。只有确需记录已发生的服药时才继续。", { expired = false }) { submit(true) }
}
