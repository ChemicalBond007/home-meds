package com.homemeds.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.homemeds.app.medicineExpiry
import com.homemeds.app.nullable
import com.homemeds.app.net.AiAgent
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun ExpiryField(value: String, change: (String) -> Unit, enabled: Boolean = true) {
    Field("有效期 YYYY-MM-DD / YYYY-MM（可空）", value, change, enabled = enabled)
    ExpiryHint(value)
}

@Composable internal fun ExpiryHint(value: String) {
    val expiry = runCatching { medicineExpiry(value) }.getOrNull()
    if (expiry?.monthOnly == true) Text("包装仅标年月，确认保存时按月末 ${expiry.date} 记录。请核对实物。", color = Muted)
}

internal typealias PackageRecognizer = suspend (List<String>, String?, suspend (String, Any) -> Unit) -> Unit

@Composable internal fun PackagePhotoControl(group: String?, enabled: Boolean,
    onBusy: (Boolean) -> Unit, recognize: PackageRecognizer = AiAgent::runPackageDetails, onDetails: (JSONObject) -> Unit) {
    var capture by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<PhotoAttachment>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val currentDetails by rememberUpdatedState(onDetails)
    val currentBusy by rememberUpdatedState(onBusy)
    Action("拍照识别有效期 / 批号", secondary = true, enabled = enabled && !loading && group != null) { capture = true }
    if (loading) {
        Text("正在识别有效期和批号…", color = Muted)
        Action("取消日期识别", secondary = true) { job?.cancel() }
    }
    notice?.let { Text(it, color = Muted) }
    if (capture) PhotoCaptureDialog({ capture = false }, photos) { selected ->
        photos = selected; capture = false; loading = true; notice = null; currentBusy(true)
        job = scope.launch {
            try {
                recognize(selected.map { it.dataUrl }, group) { event, data ->
                    if (event == "result") withContext(Dispatchers.Main) {
                        val details = data as JSONObject
                        val fields = listOf("expiry_date" to "有效期", "batch_number" to "批号")
                            .filter { details.nullable(it.first) != null }.map { it.second }
                        if (fields.isEmpty()) notice = "未识别到清楚的有效期或批号，原有内容保留。请补拍标有有效期至或 EXP 的位置，不能用生产日期代替。"
                        else {
                            currentDetails(details)
                            notice = "已预填${fields.joinToString("、")}。未识别字段保留原值，请核对同一药盒后再保存。"
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "日期识别失败，原有内容保留，请重试或手动填写" }
            finally { loading = false; currentBusy(false) }
        }
    }
}
