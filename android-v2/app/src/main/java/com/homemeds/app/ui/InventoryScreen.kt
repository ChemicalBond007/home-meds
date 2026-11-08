package com.homemeds.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.homemeds.app.*
import com.homemeds.app.net.AiAgent
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal fun boxStatus(item: StockItem, today: String): Pair<String, androidx.compose.ui.graphics.Color> = when {
    item.data.optString("status") == "discarded" -> "报废" to Muted
    item.data.optString("status") == "empty" || (item.data.optString("status") == "active" && item.remaining <= 0) -> "用尽" to Muted
    item.expiry?.let { it < today } == true -> "已过期" to Red
    else -> "在库" to Green
}

/** Box card: name, label and status, with the admin edit button visible and stock moves in "⋮". */
@Composable internal fun BoxCard(item: StockItem, today: String, admin: Boolean, busy: Boolean, edit: () -> Unit, move: (String) -> Unit) {
    val (label, color) = boxStatus(item, today)
    Panel(item.name, warning = item.expiry?.let { it < today } == true, trailing = {
        StatusChip(label, color)
        if (item.available) MoreMenu(listOf(MenuAction("用掉", !busy) { move("out") },
            MenuAction("移到另一药箱", !busy) { move("move") }, MenuAction("报废", !busy, danger = true) { move("discard") }))
    }) {
        Text(boxLabel(item, today), style = MaterialTheme.typography.bodySmall)
        if (admin) Action("修改药盒资料", secondary = true, enabled = !busy, onClick = edit)
    }
}

@Composable fun InventoryScreen(vm: AppViewModel, state: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var savedFilter by remember(state.groupId) { mutableStateOf(BoxFilter.parse(state.groupId?.let(vm.prefs::boxFilter))) }
    val filter = savedFilter.sanitize(state.warehouses.map { it.optString("id") }, state.products.map { it.id })
    var filterDialog by remember { mutableStateOf(false) }
    fun updateFilter(next: BoxFilter) {
        savedFilter = next
        state.groupId?.let { vm.prefs.setBoxFilter(it, next.toJson().toString()) }
    }
    var intake by remember { mutableStateOf(false) }
    var product by remember { mutableStateOf<Product?>(null) }
    var detail by remember { mutableStateOf<Product?>(null) }
    var codes by remember { mutableStateOf<List<String>>(emptyList()) }
    var box by remember { mutableStateOf<StockItem?>(null) }
    var movement by remember { mutableStateOf<Pair<StockItem, String>?>(null) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(tab, state.groupId) {
        if (tab == 2) try { history = vm.list("/movements").objects() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { vm.error("无法读取库存流水") }
    }
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("在库 ${state.stock.count { it.available }} 盒 · ${state.products.size} 种", fontWeight = FontWeight.SemiBold)
                Text("余量按基本单位记录，请核对实物", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Action("药品入库", enabled = state.groupId != null && !state.busy) { intake = true }
        }
        Tabs(listOf("在库药盒", "药品资料", "库存流水"), tab) { tab = it }
        Field(if (tab == 0) "搜索药名、批号、药箱或追溯码" else "搜索药名、厂家或追溯码", search, { search = it })
        if (tab == 0) BoxFilterBar(filter, state.warehouses, ::updateFilter) { filterDialog = true }
        when (tab) {
            0 -> {
                val boxes = applyBoxFilter(state.stock, filter, state.today, search)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(boxFilterSummary(filter, boxes.size, state.stock.size, state.warehouses, state.products), Modifier.weight(1f),
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (!filter.isDefault) TextButton({ updateFilter(BoxFilter()) }, Modifier.heightIn(min = touchHeight)) { Text("恢复默认") }
                }
                boxes.forEachIndexed { i, item ->
                    if (filter.sort == "warehouse" && (i == 0 || boxes[i - 1].warehouse != item.warehouse))
                        SectionHeader("${item.warehouse} · ${boxes.count { it.warehouse == item.warehouse }} 盒")
                    BoxCard(item, state.today, state.user?.admin == true, state.busy, edit = { box = item }) { movement = item to it }
                }
                if (boxes.isEmpty()) Text(if (state.stock.isEmpty()) "药箱里还没有药盒" else "当前条件下没有药盒", color = Muted)
            }
            1 -> state.products.filter { it.data.toString().contains(search, true) }.forEach { p ->
                Panel(p.name, onClick = { detail = p }, trailing = {
                    if (p.data.optString("status") == "pending") StatusChip("待人工核对", Amber) else StatusChip("已核对")
                }) {
                    Text(listOfNotNull(p.data.nullable("specification"), p.data.nullable("manufacturer"), "每盒 ${Texts.amount(p.size)} ${p.unit}")
                        .joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            2 -> RowGroup {
                val rows = history.filter { it.toString().contains(search, true) }
                rows.forEachIndexed { i, row ->
                    if (i > 0) RowDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("${row.optString("name")} · ${movementLabel(row.optString("type"))} ${row.opt("amount")}", fontWeight = FontWeight.SemiBold)
                        Text("${row.optString("username")} · ${row.optString("created_at")}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (rows.isEmpty()) Text("暂无库存流水", Modifier.padding(14.dp), color = Muted)
            }
        }
    }
    if (filterDialog) BoxFilterDialog(filter, state.warehouses, state.products, ::updateFilter) { filterDialog = false }
    if (intake) IntakeDialog(vm, state) { intake = false }
    detail?.let { p -> Modal(p.name, { detail = null }) {
        Text(listOfNotNull(p.data.nullable("specification"), p.data.nullable("manufacturer")).joinToString(" · "), color = Muted)
        Text("每盒 ${Texts.amount(p.size)} ${p.unit} · ${if (p.data.optString("status") == "pending") "待人工核对" else "已核对"}")
        p.data.nullable("generic_name")?.let { Text("通用名：$it") }
        p.data.nullable("indications")?.let { Text("适应症：$it") }
        p.data.nullable("precautions")?.let { Text("注意事项：$it") }
        Sources(parseSources(p.data.opt("sources")))
        if (state.user?.admin == true) Action("编辑资料及条码映射", secondary = true, enabled = !state.busy) {
            vm.perform { codes = vm.list("/products/${pathSegment(p.id)}/codes").strings(); detail = null; product = p }
        }
    } }
    product?.let { p -> ProductEditor(p.data, codes, state.busy, { product = null }) { body ->
        vm.save("PUT", "/products/${pathSegment(p.id)}", body) { product = null }
    } }
    box?.let { item ->
        var photoBusy by remember(item.id) { mutableStateOf(false) }
        val initial = json("expiry_date" to item.expiry, "batch_number" to item.data.nullable("batch_number"),
            "serial_number" to item.data.nullable("serial_number"), "trace_code" to item.trace,
            "units_per_package" to item.data.getDouble("units_per_package"), "opened" to (item.data.optInt("opened") == 1),
            "expected_version" to item.version)
        EditForm("编辑药盒资料（保存时检查版本）", listOf(FormField("expiry_date", "有效期", "expiry"),
            FormField("batch_number", "批号", "nullable"), FormField("serial_number", "序列号", "nullable"),
            FormField("trace_code", "20 位追溯码", "nullable"), FormField("units_per_package", "每盒数量", "number"),
            FormField("opened", "已经开封", "bool")), initial, state.busy || photoBusy, { box = null }, extra = { values ->
            PackagePhotoControl(state.groupId, !state.busy, { photoBusy = it }) { details ->
                details.nullable("expiry_date")?.let { values["expiry_date"] = it }
                details.nullable("batch_number")?.let { values["batch_number"] = it }
            }
        }) { body ->
            vm.perform {
                try { vm.mutate("PUT", "/stock/${pathSegment(item.id)}", body); box = null }
                catch (e: com.homemeds.app.net.ApiException) {
                    if (e.status == 409) { box = null; vm.refresh() }
                    throw e
                }
            }
        }
    }
    movement?.let { (item, type) ->
        if (type == "discard") Confirm("确认报废", "将 ${item.name} 的本药盒剩余 ${Texts.amount(item.remaining)} ${item.unit} 全部报废，不能撤销。", { movement = null }) {
            vm.save("POST", "/stock/${pathSegment(item.id)}/move", json("type" to "discard")) { movement = null }
        } else EditForm(if (type == "out") "记录用掉" else "移动药盒",
            if (type == "out") listOf(FormField("amount", "用掉数量（${item.unit}）", "number"))
            else listOf(FormField("warehouse_id", "目标药箱", "choice", state.warehouses.filter { it.getString("id") != item.data.optString("warehouse_id") }.map { it.getString("id") to it.getString("name") })),
            json("type" to type, "amount" to 1), state.busy, { movement = null }) {
                vm.save("POST", "/stock/${pathSegment(item.id)}/move", it) { movement = null }
            }
    }
}

private fun warehouseOptions(warehouses: List<JSONObject>) =
    listOf("" to "全部药箱") + warehouses.map { it.getString("id") to it.getString("name") }

/** Quick status / warehouse / sort chips; less common conditions live in [BoxFilterDialog]. */
@Composable internal fun BoxFilterBar(filter: BoxFilter, warehouses: List<JSONObject>, change: (BoxFilter) -> Unit, more: () -> Unit) {
    ActionRow {
        ChipPicker(filter.status, BoxFilter.statuses, { change(filter.copy(status = it)) }, description = "状态筛选")
        ChipPicker(filter.warehouseId, warehouseOptions(warehouses), { change(filter.copy(warehouseId = it)) }, description = "药箱筛选")
        ChipPicker(filter.sort, BoxFilter.sorts, { change(filter.copy(sort = it)) }, description = "排序方式")
        val extra = listOf(filter.productId, filter.expiry, filter.opened).count(String::isNotEmpty)
        Action(if (extra > 0) "更多筛选 · $extra" else "更多筛选", secondary = true, onClick = more)
    }
}

@Composable internal fun BoxFilterDialog(filter: BoxFilter, warehouses: List<JSONObject>, products: List<Product>,
    change: (BoxFilter) -> Unit, dismiss: () -> Unit) {
    Modal("筛选与排序药盒", dismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("状态", filter.status, BoxFilter.statuses, { change(filter.copy(status = it)) }, modifier = Modifier.weight(1f))
            Picker("药箱", filter.warehouseId, warehouseOptions(warehouses), { change(filter.copy(warehouseId = it)) }, modifier = Modifier.weight(1f))
        }
        Picker("药品", filter.productId, listOf("" to "全部药品") + products.map { it.id to it.name }, { change(filter.copy(productId = it)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("有效期", filter.expiry, BoxFilter.expiries, { change(filter.copy(expiry = it)) }, modifier = Modifier.weight(1f))
            Picker("开封", filter.opened, BoxFilter.openedStates, { change(filter.copy(opened = it)) }, modifier = Modifier.weight(1f))
        }
        Picker("排序", filter.sort, BoxFilter.sorts, { change(filter.copy(sort = it)) })
        Text("筛选条件按家庭保存在本机，下次打开药箱仍然生效。", color = Muted, style = MaterialTheme.typography.bodySmall)
        ActionRow {
            Action("恢复默认", secondary = true, enabled = !filter.isDefault) { change(BoxFilter()) }
            Action("完成", onClick = dismiss)
        }
    }
}

internal fun boxFilterSummary(filter: BoxFilter, shown: Int, total: Int, warehouses: List<JSONObject>, products: List<Product>): String {
    fun label(options: List<Pair<String, String>>, value: String) = options.find { it.first == value }?.second
    val parts = listOfNotNull(
        filter.status.takeIf(String::isNotEmpty)?.let { label(BoxFilter.statuses, it) },
        filter.warehouseId.takeIf(String::isNotEmpty)?.let { id -> warehouses.find { it.optString("id") == id }?.optString("name") },
        filter.productId.takeIf(String::isNotEmpty)?.let { id -> products.find { it.id == id }?.name },
        filter.expiry.takeIf(String::isNotEmpty)?.let { label(BoxFilter.expiries, it) },
        filter.opened.takeIf(String::isNotEmpty)?.let { label(BoxFilter.openedStates, it) },
    )
    return (listOf("显示 $shown / $total 盒") + parts + "按${label(BoxFilter.sorts, filter.sort)}排序").joinToString(" · ")
}

fun movementLabel(type: String) = when (type) {
    "in" -> "入库"; "out" -> "用掉"; "move" -> "移库"; "discard" -> "报废"; "dose" -> "服药扣库存"; "undo_dose" -> "撤销服药"; else -> type
}
fun parseSources(value: Any?): List<JSONObject> = runCatching {
    when (value) { is JSONArray -> value; is String -> JSONArray(value); else -> JSONArray() }.objects()
}.getOrDefault(emptyList())

@Composable fun Sources(sources: List<JSONObject>) {
    val context = LocalContext.current
    val public = sources.filter { runCatching { UrlPolicy.publicUrl(it.optString("url")) }.isSuccess }
    if (public.isNotEmpty()) ActionRow {
        public.forEach { s ->
            val url = s.optString("url")
            Action(s.optString("title", url), secondary = true) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
    }
}

private val productFields = listOf(
    FormField("name", "药品名称"), FormField("generic_name", "通用名", "nullable"),
    FormField("manufacturer", "生产厂家", "nullable"), FormField("specification", "规格", "nullable"),
    FormField("indications", "适应症（仅抄录说明书）", "multiline"), FormField("precautions", "注意事项", "multiline"),
    FormField("base_unit", "基本单位"), FormField("units_per_package", "每盒数量", "number"),
    FormField("status", "资料状态", "choice", listOf("pending" to "待人工核对", "confirmed" to "已核对实物 / 说明书")),
    FormField("source_urls", "来源链接（每行一个，仅公开资料）", "multiline"),
)
@Composable fun ProductEditor(initial: JSONObject, codes: List<String>?, busy: Boolean, dismiss: () -> Unit, save: (JSONObject) -> Unit) {
    val seed = remember {
        JSONObject(initial.toString()).apply {
            if (!has("base_unit")) put("base_unit", "片")
            if (!has("units_per_package")) put("units_per_package", 1)
            if (!has("status")) put("status", "pending")
            put("source_urls", parseSources(opt("sources")).joinToString("\n") { it.getString("url") })
            if (codes != null) put("codes_text", codes.joinToString("\n"))
        }
    }
    EditForm("核对药品资料", productFields + if (codes != null) listOf(FormField("codes_text", "条码映射（每行一个，删除行会解除关联）", "multiline")) else emptyList(),
        seed, busy, dismiss) { body ->
        listOf("generic_name", "manufacturer", "specification", "indications", "precautions").forEach { if (body.optString(it).isBlank()) body.put(it, JSONObject.NULL) }
        val sources = body.getString("source_urls").lines().map(String::trim).filter(String::isNotBlank).map {
            val url = UrlPolicy.publicUrl(it)
            json("title" to url.host, "url" to url.toString())
        }
        body.put("sources", JSONArray(sources))
        if (codes != null) body.put("codes", JSONArray(body.optString("codes_text").lines().map(String::trim).filter(String::isNotBlank).distinct()))
        body.remove("source_urls"); body.remove("codes_text")
        save(body)
    }
}

/** Exact, unambiguous name/spec/manufacturer matching only; never pick an arbitrary variant. */
internal fun matchMedicineDraft(draft: JSONObject, products: List<Product>): Product? {
    val name = draft.nullable("name")?.trim()?.takeIf(String::isNotBlank) ?: return null
    return products.filter { p ->
        p.name.trim().equals(name, true) && listOf("specification", "manufacturer").all { key ->
            val value = draft.nullable(key)?.trim().orEmpty()
            value.isEmpty() || p.data.nullable(key)?.trim().equals(value, true)
        }
    }.singleOrNull()
}

internal data class IntakeDraftSeed(val product: Product?, val code: String, val expiry: String, val batch: String, val size: String)
internal fun prepareIntakeDraft(draft: JSONObject, products: List<Product>, stock: List<StockItem> = emptyList()): IntakeDraftSeed {
    val code = draft.nullable("code").orEmpty()
    val box = knownTraceBox(code, stock)
    val product = if (box != null) products.firstOrNull { it.id == box.productId } else matchMedicineDraft(draft, products)
    val parsed = parseCode(code)
    return IntakeDraftSeed(product, code, draft.nullable("expiry_date") ?: parsed.expiryDate.orEmpty(),
        draft.nullable("batch_number") ?: parsed.batchNumber.orEmpty(),
        if (!draft.isNull("units_per_package")) draft.get("units_per_package").toString() else product?.size?.let(Texts::amount).orEmpty())
}

@Composable fun IntakeDialog(vm: AppViewModel, state: AppState, initialCode: String = "", initialDraft: JSONObject? = null,
    onSaved: () -> Unit = {}, dismiss: () -> Unit) {
    var code by remember { mutableStateOf(initialCode) }
    var scan by remember { mutableStateOf(false) }
    var product by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(1) }
    var newProduct by remember { mutableStateOf<JSONObject?>(null) }
    var query by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var lookup by remember { mutableStateOf<Job?>(null) }
    var looking by remember { mutableStateOf(false) }
    var warehouse by remember { mutableStateOf(state.warehouses.firstOrNull()?.optString("id").orEmpty()) }
    var expiry by remember { mutableStateOf(parseCode(initialCode).expiryDate.orEmpty()) }
    var batch by remember { mutableStateOf(parseCode(initialCode).batchNumber.orEmpty()) }
    var size by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf("1") }
    var checked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var photoDialog by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<PhotoAttachment>>(emptyList()) }
    var recognized by remember { mutableStateOf<JSONObject?>(null) }
    var advanceAfterCreate by remember { mutableStateOf(false) }
    var duplicateCode by remember { mutableStateOf<String?>(null) }
    val duplicate = knownTraceBox(code, state.stock) != null || (duplicateCode != null && duplicateCode == code.trim())
    val scope = rememberCoroutineScope()
    fun applyDraft(draft: JSONObject) {
        recognized = JSONObject(draft.toString()).put("status", "pending")
        val seed = prepareIntakeDraft(draft, state.products, state.stock)
        code = seed.code; expiry = seed.expiry; batch = seed.batch; size = seed.size
        checked = false
        if (knownTraceBox(code, state.stock) != null) {
            product = seed.product?.id.orEmpty(); newProduct = null; advanceAfterCreate = false
            error = "已识别此追溯码对应的已入库药盒，不能重复添加"
            return
        }
        if (seed.product != null) {
            product = seed.product.id
            advanceAfterCreate = false
        } else {
            product = ""; advanceAfterCreate = true; newProduct = recognized
        }
    }
    fun lookupMedicine(images: List<PhotoAttachment> = emptyList()) {
        if (looking) return
        error = null; looking = true; photos = images.toList()
        lookup = scope.launch {
            try {
                AiAgent.runLookup(query.trim().ifBlank { "请识别图中药品容器信息" }.take(500), state.groupId,
                    imageDataUrls = images.map { it.dataUrl }) { event, data -> withContext(Dispatchers.Main) {
                    if (event == "progress") progress = data.toString()
                    if (event == "result") applyDraft(data as JSONObject)
                } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "AI 查询失败，请手动填写" }
            finally { progress = ""; looking = false }
        }
    }
    suspend fun resolve(raw: String) {
        val data = resolveMedicineCode(vm.get("/codes/resolve?code=" + URLEncoder.encode(raw, "UTF-8")), raw, state.products, state.stock)
        val parsed = parseCode(raw)
        expiry = parsed.expiryDate.orEmpty(); batch = parsed.batchNumber.orEmpty()
        val match = data.optJSONObject("product")
        product = match?.getString("id").orEmpty(); size = match?.get("units_per_package")?.toString().orEmpty()
        newProduct = null; advanceAfterCreate = false; checked = false; error = null
        duplicateCode = if (data.optJSONObject("box") != null) raw.trim() else null
        if (duplicateCode != null) { error = "已识别此追溯码对应的已入库药盒，不能重复添加"; return }
        data.optJSONObject("cache")?.let { newProduct = it }
    }
    LaunchedEffect(Unit) {
        if (initialDraft != null) applyDraft(initialDraft)
        else if (initialCode.isNotBlank()) try { resolve(initialCode) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "条码解析失败，可重试或手动录入" }
    }
    Modal("药品入库 · 第 $step 步", dismiss) {
        val idle = !looking && !state.busy
        if (step == 1) {
            Field("条码或追溯码（可留空手工入库）", code, { code = it; checked = false }, enabled = idle)
            ActionRow {
                Action("相机扫码", secondary = true, enabled = idle) { scan = true }
                Action("拍照识药", secondary = true, enabled = idle) { photoDialog = true }
                Action("查询条码", secondary = true, enabled = idle && code.isNotBlank()) { vm.perform { resolve(code) } }
            }
            if (photos.isNotEmpty()) Text("本次识药已附 ${photos.size} 张多角度照片，可重新打开拍照入口补拍或移除。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("选择已有药品", product, listOf("" to "请选择") + state.products.map { it.id to it.name }, {
                    product = it; size = state.products.find { p -> p.id == it }?.size?.let(Texts::amount).orEmpty()
                }, enabled = idle, modifier = Modifier.weight(1f))
                Action("新建药品资料", secondary = true, enabled = idle) { newProduct = JSONObject() }
            }
            if (recognized != null && product.isNotBlank()) Action("按识别结果新建药品资料", secondary = true, enabled = idle) {
                advanceAfterCreate = true; newProduct = recognized
            }
            Collapsible("AI 检索（可选）", open = looking || progress.isNotBlank()) {
                Field("AI 检索药名 / 厂家 / 条码", query, { query = it }, enabled = idle)
                Text("AI 仅预填公开资料或照片可见文字，必须逐项核对药名、数量、有效期和批号。", color = Red, style = MaterialTheme.typography.bodySmall)
                ActionRow {
                    Action("AI 检索预填", enabled = idle && query.isNotBlank()) { lookupMedicine() }
                    if (looking) Action("取消查询", secondary = true) { lookup?.cancel() }
                }
                if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall)
            }
            if (looking && progress.isBlank()) Text("正在识别…", color = Muted)
            if (duplicate) Text("该追溯码对应已入库药盒，可以查询，但不能重复入库。", color = Muted)
            Action("下一步：核对药盒", enabled = product.isNotBlank() && !duplicate && idle) { step = 2; error = null }
        } else {
            Text(state.products.find { it.id == product }?.name ?: "新建药品", style = MaterialTheme.typography.titleMedium)
            Picker("存放药箱", warehouse, state.warehouses.map { it.getString("id") to it.getString("name") }, { warehouse = it; checked = false })
            PackagePhotoControl(state.groupId, idle, { looking = it }) { details ->
                details.nullable("expiry_date")?.let { expiry = it }
                details.nullable("batch_number")?.let { batch = it }
                checked = false
            }
            ExpiryField(expiry, { expiry = it; checked = false }, enabled = idle)
            Field("批号（可空）", batch, { batch = it; checked = false }, enabled = idle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("每盒数量", size, { size = it; checked = false }, Modifier.weight(1f), number = true)
                Field("盒数", packages, { packages = it; checked = false }, Modifier.weight(1f), number = true)
            }
            Text("追溯码只能一盒。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Check("已核对实物药名、规格、数量、有效期和存放药箱", checked, { checked = it }, enabled = idle)
            ActionRow {
                Action("上一步", secondary = true, enabled = idle) { step = 1 }
                Action("确认入库", enabled = checked && !duplicate && idle) {
                try {
                    require(!duplicate) { "该追溯码药盒已入库，不能重复添加" }
                    require(warehouse.isNotBlank()) { "请选择药箱" }
                    val n = size.toDoubleOrNull()
                    require(n != null && n.isFinite() && n > 0) { "请填写有效的每盒数量" }
                    val count = packages.toIntOrNull()
                    require(count != null && count in 1..100) { "盒数应为 1–100" }
                    require(parseCode(code).traceCode == null || count == 1) { "一个追溯码只能入库一盒" }
                    val expiryDate = medicineExpiry(expiry)?.date?.toString()
                    vm.save("POST", "/stock", json("product_id" to product, "warehouse_id" to warehouse, "code" to code,
                        "expiry_date" to expiryDate, "batch_number" to batch.ifBlank { null },
                        "units_per_package" to n, "packages" to count)) { onSaved(); dismiss() }
                } catch (e: Exception) { error = e.message ?: "请核对填写内容" }
            }
            }
        }
        error?.let { Text(it, color = Red) }
    }
    if (scan) ScannerDialog({ scan = false }) { raw ->
        code = raw; scan = false
        vm.perform { resolve(raw) }
    }
    if (photoDialog) PhotoCaptureDialog({ photoDialog = false }, initialPhotos = photos) { selected ->
        photoDialog = false
        photos = selected
        lookupMedicine(selected)
    }
    newProduct?.let { seed -> ProductEditor(seed, null, state.busy, { newProduct = null }) { body ->
        body.put("code", code)
        vm.perform {
            val created = vm.mutate("POST", "/products", body)
            product = created.getString("id"); size = body.get("units_per_package").toString(); newProduct = null
            if (advanceAfterCreate) { step = 2; advanceAfterCreate = false }
        }
    } }
}
