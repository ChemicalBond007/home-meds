package com.homemeds.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.homemeds.app.*
import com.homemeds.app.net.AiAgent
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

@Composable fun InventoryScreen(vm: AppViewModel, state: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("active") }
    var intake by remember { mutableStateOf(false) }
    var product by remember { mutableStateOf<Product?>(null) }
    var codes by remember { mutableStateOf<List<String>>(emptyList()) }
    var box by remember { mutableStateOf<StockItem?>(null) }
    var movement by remember { mutableStateOf<Pair<StockItem, String>?>(null) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(tab, state.groupId) {
        if (tab == 2) try { history = vm.list("/movements").objects() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { vm.error("无法读取库存流水") }
    }
    Page("家庭药箱") {
        Panel {
            Text("在库 ${state.stock.count { it.available }} 盒 · 药品 ${state.products.size} 种")
            Text("余量按各药品基本单位记录，请核对实物。")
            Action("药品入库", enabled = state.groupId != null && !state.busy) { intake = true }
        }
        Tabs(listOf("在库药盒", "药品资料", "库存流水"), tab) { tab = it }
        Field("搜索药名、厂家或追溯码", search, { search = it })
        when (tab) {
            0 -> {
                Picker("状态", status, listOf("active" to "在库", "empty" to "用尽", "discarded" to "报废", "" to "全部"), { status = it })
                state.stock.filter { (status.isEmpty() || it.data.optString("status") == status) &&
                    (it.name.contains(search, true) || it.trace.orEmpty().contains(search)) }.forEach { item ->
                    Panel(item.name, warning = item.expiry?.let { it < state.today } == true) {
                        Text(boxLabel(item, state.today))
                        if (item.available) {
                            Action("用掉", secondary = true, enabled = !state.busy) { movement = item to "out" }
                            Action("移到另一药箱", secondary = true, enabled = !state.busy) { movement = item to "move" }
                            Action("报废", secondary = true, enabled = !state.busy) { movement = item to "discard" }
                        }
                        if (state.user?.admin == true) Action("修改药盒资料", secondary = true, enabled = !state.busy) { box = item }
                    }
                }
            }
            1 -> state.products.filter { it.data.toString().contains(search, true) }.forEach { p ->
                Panel(p.name) {
                    Text("${p.data.nullable("specification").orEmpty()} · ${p.data.nullable("manufacturer").orEmpty()}")
                    Text("每盒 ${Texts.amount(p.size)} ${p.unit} · ${if (p.data.optString("status") == "pending") "待人工核对" else "已核对"}")
                    p.data.nullable("generic_name")?.let { Text("通用名：$it") }
                    p.data.nullable("indications")?.let { Text("适应症：$it") }
                    p.data.nullable("precautions")?.let { Text("注意事项：$it") }
                    Sources(parseSources(p.data.opt("sources")))
                    if (state.user?.admin == true) Action("编辑资料及条码映射", secondary = true, enabled = !state.busy) {
                        vm.perform { codes = vm.list("/products/${p.id}/codes").strings(); product = p }
                    }
                }
            }
            2 -> history.filter { it.toString().contains(search, true) }.forEach { row ->
                Panel(row.optString("name")) {
                    Text("${movementLabel(row.optString("type"))} · ${row.opt("amount")} · ${row.optString("username")}")
                    Text(row.optString("created_at"), color = Muted)
                }
            }
        }
    }
    if (intake) IntakeDialog(vm, state) { intake = false }
    product?.let { p -> ProductEditor(p.data, codes, state.busy, { product = null }) { body ->
        vm.save("PUT", "/products/${p.id}", body) { product = null }
    } }
    box?.let { item ->
        val initial = json("expiry_date" to item.expiry, "batch_number" to item.data.nullable("batch_number"),
            "serial_number" to item.data.nullable("serial_number"), "trace_code" to item.trace,
            "units_per_package" to item.data.getDouble("units_per_package"), "opened" to (item.data.optInt("opened") == 1),
            "expected_version" to item.version)
        EditForm("编辑药盒资料（保存时检查版本）", listOf(FormField("expiry_date", "有效期 YYYY-MM-DD", "nullable"),
            FormField("batch_number", "批号", "nullable"), FormField("serial_number", "序列号", "nullable"),
            FormField("trace_code", "20 位追溯码", "nullable"), FormField("units_per_package", "每盒数量", "number"),
            FormField("opened", "已经开封", "bool")), initial, state.busy, { box = null }) { body ->
            vm.perform {
                try { vm.mutate("PUT", "/stock/${item.id}", body); box = null }
                catch (e: com.homemeds.app.net.ApiException) {
                    if (e.status == 409) { box = null; vm.refresh() }
                    throw e
                }
            }
        }
    }
    movement?.let { (item, type) ->
        if (type == "discard") Confirm("确认报废", "将 ${item.name} 的本药盒剩余 ${Texts.amount(item.remaining)} ${item.unit} 全部报废，不能撤销。", { movement = null }) {
            vm.save("POST", "/stock/${item.id}/move", json("type" to "discard")) { movement = null }
        } else EditForm(if (type == "out") "记录用掉" else "移动药盒",
            if (type == "out") listOf(FormField("amount", "用掉数量（${item.unit}）", "number"))
            else listOf(FormField("warehouse_id", "目标药箱", "choice", state.warehouses.filter { it.getString("id") != item.data.optString("warehouse_id") }.map { it.getString("id") to it.getString("name") })),
            json("type" to type, "amount" to 1), state.busy, { movement = null }) {
                vm.save("POST", "/stock/${item.id}/move", it) { movement = null }
            }
    }
}

fun movementLabel(type: String) = when (type) {
    "in" -> "入库"; "out" -> "用掉"; "move" -> "移库"; "discard" -> "报废"; "dose" -> "服药扣库存"; "undo_dose" -> "撤销服药"; else -> type
}
fun parseSources(value: Any?): List<JSONObject> = runCatching {
    when (value) { is JSONArray -> value; is String -> JSONArray(value); else -> JSONArray() }.objects()
}.getOrDefault(emptyList())

@Composable fun Sources(sources: List<JSONObject>) {
    val context = LocalContext.current
    sources.forEach { s ->
        val url = s.optString("url")
        if (runCatching { UrlPolicy.publicUrl(url) }.isSuccess) Action(s.optString("title", url), secondary = true) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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

@Composable fun IntakeDialog(vm: AppViewModel, state: AppState, initialCode: String = "", dismiss: () -> Unit) {
    var code by remember { mutableStateOf(initialCode) }
    var scan by remember { mutableStateOf(false) }
    var product by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(1) }
    var newProduct by remember { mutableStateOf<JSONObject?>(null) }
    var query by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var lookup by remember { mutableStateOf<Job?>(null) }
    var warehouse by remember { mutableStateOf(state.warehouses.firstOrNull()?.optString("id").orEmpty()) }
    var expiry by remember { mutableStateOf(parseCode(initialCode).expiryDate.orEmpty()) }
    var batch by remember { mutableStateOf(parseCode(initialCode).batchNumber.orEmpty()) }
    var size by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf("1") }
    var checked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun resolve(raw: String) {
        val data = vm.get("/codes/resolve?code=" + URLEncoder.encode(raw, "UTF-8"))
        val parsed = parseCode(raw)
        expiry = parsed.expiryDate.orEmpty(); batch = parsed.batchNumber.orEmpty()
        if (data.optJSONObject("box") != null) { error = "该追溯码药盒已入库，不能重复添加"; product = ""; return }
        data.optJSONObject("product")?.let { product = it.getString("id"); size = it.get("units_per_package").toString() }
        data.optJSONObject("cache")?.let { newProduct = it }
    }
    LaunchedEffect(initialCode) { if (initialCode.isNotBlank()) try { resolve(initialCode) } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "条码解析失败，可重试或手动录入" } }
    Modal("药品入库 · 第 $step 步", dismiss) {
        if (step == 1) {
            Field("条码或追溯码（可留空手工入库）", code, { code = it; checked = false })
            Action("相机扫码", secondary = true) { scan = true }
            Action("查询条码", secondary = true, enabled = !state.busy && code.isNotBlank()) { vm.perform { resolve(code) } }
            Picker("选择已有药品", product, listOf("" to "请选择") + state.products.map { it.id to it.name }, {
                product = it; size = state.products.find { p -> p.id == it }?.size?.let(Texts::amount).orEmpty()
            })
            Action("新建药品资料", secondary = true) { newProduct = JSONObject() }
            Field("AI 检索药名 / 厂家 / 条码", query, { query = it })
            Text("AI 只预填公开资料，必须逐项核对后保存。", color = Red)
            Action("AI 检索预填", enabled = lookup?.isActive != true && query.isNotBlank()) {
                lookup = scope.launch {
                    try {
                        AiAgent.runLookup(query.trim().take(500), state.groupId) { event, data -> withContext(Dispatchers.Main) {
                            if (event == "progress") progress = data.toString()
                            if (event == "result") newProduct = (data as JSONObject).put("status", "pending")
                        } }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "AI 查询失败，请手动填写" }
                    finally { progress = "" }
                }
            }
            if (lookup?.isActive == true) Action("取消查询", secondary = true) { lookup?.cancel() }
            if (progress.isNotBlank()) Text(progress)
            Action("下一步：核对药盒", enabled = product.isNotBlank() && lookup?.isActive != true) { step = 2; error = null }
        } else {
            Text(state.products.find { it.id == product }?.name ?: "新建药品")
            Picker("存放药箱", warehouse, state.warehouses.map { it.getString("id") to it.getString("name") }, { warehouse = it })
            Field("有效期 YYYY-MM-DD（可空）", expiry, { expiry = it; checked = false })
            Field("批号（可空）", batch, { batch = it; checked = false })
            Field("每盒数量", size, { size = it; checked = false }, number = true)
            Field("盒数（追溯码只能一盒）", packages, { packages = it; checked = false }, number = true)
            Check("已核对实物药名、规格、数量、有效期和存放药箱", checked, { checked = it })
            Action("确认入库", enabled = checked && !state.busy) {
                try {
                    require(warehouse.isNotBlank()) { "请选择药箱" }
                    val n = size.toDoubleOrNull()
                    require(n != null && n.isFinite() && n > 0) { "请填写有效的每盒数量" }
                    val count = packages.toIntOrNull()
                    require(count != null && count in 1..100) { "盒数应为 1–100" }
                    require(parseCode(code).traceCode == null || count == 1) { "一个追溯码只能入库一盒" }
                    if (expiry.isNotBlank()) java.time.LocalDate.parse(expiry)
                    vm.save("POST", "/stock", json("product_id" to product, "warehouse_id" to warehouse, "code" to code,
                        "expiry_date" to expiry.ifBlank { null }, "batch_number" to batch.ifBlank { null },
                        "units_per_package" to n, "packages" to count)) { dismiss() }
                } catch (e: Exception) { error = e.message ?: "请核对填写内容" }
            }
            Action("上一步", secondary = true, enabled = !state.busy) { step = 1 }
        }
        error?.let { Text(it, color = Red) }
    }
    if (scan) ScannerDialog({ scan = false }) { raw ->
        code = raw; scan = false
        vm.perform { resolve(raw) }
    }
    newProduct?.let { seed -> ProductEditor(seed, null, state.busy, { newProduct = null }) { body ->
        body.put("code", code)
        vm.perform {
            val created = vm.mutate("POST", "/products", body)
            product = created.getString("id"); size = body.get("units_per_package").toString(); newProduct = null
        }
    } }
}
