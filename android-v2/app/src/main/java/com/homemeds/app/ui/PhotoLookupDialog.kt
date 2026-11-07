package com.homemeds.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.homemeds.app.*
import com.homemeds.app.net.AiAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun MedicinePhotoResult(state: AppState, draft: JSONObject, intake: () -> Unit) {
    Text(draft.getString("name"), style = MaterialTheme.typography.titleLarge)
    listOf("generic_name" to "通用名", "manufacturer" to "厂家", "specification" to "规格",
        "indications" to "适应症", "precautions" to "注意事项", "base_unit" to "基本单位",
        "units_per_package" to "每盒数量", "expiry_date" to "有效期", "batch_number" to "批号",
        "code" to "条码 / 追溯码").forEach { (key, label) ->
        if (!draft.isNull(key)) Text("$label：${draft.get(key)}")
    }
    val sources = parseSources(draft.opt("sources"))
    Sources(sources)
    if (sources.isEmpty()) Text("本次仅按照片可见文字预填，没有公开资料来源。", color = Muted)
    val existingBox = knownTraceBox(draft.nullable("code").orEmpty(), state.stock)
    val match = if (existingBox != null) state.products.firstOrNull { it.id == existingBox.productId } else matchMedicineDraft(draft, state.products)
    if (match != null) {
        Text("已匹配家庭药品，可继续核对实物入库。")
        state.stock.filter { it.productId == match.id && it.available }.forEach { Text(boxLabel(it, state.today)) }
    } else Text("未找到唯一匹配的家庭药品，入库前需先核对药品资料。")
    val duplicate = existingBox != null
    if (duplicate) Text("该追溯码药盒已入库，请勿重复添加。", color = Red)
    Text("尚未保存。请核对同一药盒的药名、规格、数量、有效期和批号。不同药盒请分开查询。", color = Red)
    Action("核对并入库", enabled = !duplicate && !state.busy, onClick = intake)
}

@Composable internal fun PhotoLookupDialog(state: AppState, photos: List<PhotoAttachment>, dismiss: () -> Unit,
    revise: () -> Unit, intake: (JSONObject) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(attempt, photos, state.groupId) {
        loading = true; error = null; result = null
        progress = "正在综合识别 ${photos.size} 张多角度照片…"
        try {
            AiAgent.runLookup("请综合同一药盒的多角度照片查询药品资料，提取有效期、批号和条码，仅生成待人工核对资料",
                state.groupId, imageDataUrls = photos.map { it.dataUrl }) { event, data ->
                withContext(Dispatchers.Main) {
                    if (event == "progress") progress = data.toString()
                    if (event == "result") result = data as JSONObject
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "照片查询失败，请重试或补拍" }
        finally { loading = false; progress = "" }
    }
    Modal("照片识药结果", dismiss) {
        Text("已提交 ${photos.size} 张同一药盒的照片。查询不会自动保存药品或库存。")
        if (loading) Text(progress)
        error?.let { Text(it, color = Red) }
        result?.let { draft -> MedicinePhotoResult(state, draft) { intake(draft) } }
        if (error != null) Action("重新识别", enabled = !loading) { attempt++ }
        Action("补拍 / 修改照片", secondary = true, enabled = !loading, onClick = revise)
    }
}
