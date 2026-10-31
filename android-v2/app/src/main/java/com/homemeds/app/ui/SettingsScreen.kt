package com.homemeds.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homemeds.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun SettingsScreen(vm: AppViewModel, state: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    Page("设置") {
        if (state.user?.admin == true) Tabs(listOf("提醒与账号", "家庭管理", "AI 配置", "使用日志"), tab) { tab = it }
        when (tab) {
            0 -> CalendarPanel(vm, state)
            1 -> if (state.user?.admin == true) FamilyPanel(vm, state)
            2 -> if (state.user?.admin == true) AiSettingsPanel(vm, state)
            3 -> if (state.user?.admin == true) LogsPanel(vm, state)
        }
    }
}

@Composable private fun CalendarPanel(vm: AppViewModel, state: AppState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val calendarPermission = LocalCalendarPermission.current
    val notifications = LocalNotificationPermission.current
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(Sync.status(context)) }
    var members by remember { mutableStateOf(vm.prefs.members.toSet()) }
    var calendarAlerts by remember { mutableStateOf(vm.prefs.calendarAlerts) }
    var appAlerts by remember { mutableStateOf(vm.prefs.appNotifications) }
    var recovery by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }
    var codes by remember { mutableStateOf<List<String>?>(null) }
    var regenerate by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { revision++; vm.syncDebounced() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, state.notice, state.busy) { status = Sync.status(context) }
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { vm.error("手机不支持此设置入口，请在系统设置中手动打开") } }
    Panel("本地日历与提醒") {
        Text("所选成员的定时计划会写入手机本地「家药箱」日历，每条日程 15 分钟并按 RRULE 重复。")
        state.members.forEach { user -> Check("同步 ${user.username}", user.id in members, {
            members = if (it) members + user.id else members - user.id
            vm.prefs.members = members.toList(); vm.syncDebounced()
        }) }
        Check("日历到点响铃", calendarAlerts, { calendarAlerts = it; vm.prefs.calendarAlerts = it; vm.syncDebounced() })
        Check("应用到点通知（本人 + 家人超时）", appAlerts, { appAlerts = it; vm.prefs.appNotifications = it; vm.syncDebounced() })
        Text("本人按时提醒；非老人用户可接收所选家人超 30 分钟未记录的提醒。通知前联网核对，离线时明确标记未核对。")
        Text("日历权限：${if (status.optBoolean("calendar_permission")) "已允许" else "未允许"}")
        Action("申请日历权限", secondary = true, onClick = calendarPermission)
        Text("通知权限：${if (status.optBoolean("notification_permission")) "已允许" else "未允许"}")
        Action("申请通知权限", secondary = true, onClick = notifications)
        Action("系统通知设置", secondary = true) { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        Text("精确闹钟：${if (status.optBoolean("exact_alarm")) "已允许" else "未允许，将尽力提醒"}")
        if (Build.VERSION.SDK_INT >= 31) Action("允许精确闹钟", secondary = true) {
            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
        Action("检查电池优化设置", secondary = true) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        Text("上次同步：" + vm.prefs.lastSync?.let {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
        }.orEmpty())
        Text("日历 ${vm.prefs.events} 条 · 未来 48 小时提醒 ${vm.prefs.alarms} 个")
        vm.prefs.lastError?.let { Text(it, color = Red) }
        Action("立即同步", enabled = !state.busy) { vm.syncNow(); revision++ }
        Text("后台每 6 小时同步一次；部分手机需手动允许自启动和后台运行。", color = Muted)
    }
    Panel("账号与安全") {
        Text("${state.user?.username} · ${if (state.user?.admin == true) "管理员" else "家庭成员"}")
        if (state.user?.admin == true) {
            Action("查看验证器与恢复码状态", secondary = true, enabled = !state.busy) { vm.perform { recovery = vm.get("/auth/recovery") } }
            recovery?.let { r -> Text("验证器：${if (r.optBoolean("totp")) "已绑定" else "未绑定"} · 恢复码剩余 ${r.optInt("remaining")} 个 · 通行密钥 ${r.optInt("passkeys")} 个") }
            Text("服务端只保存恢复码摘要，不能再次查看原码。可验证身份后重新生成。", color = Muted)
            Field("验证器 6 位验证码", code, { code = it.filter(Char::isDigit).take(6) }, secret = true)
            Action("重新生成恢复码", secondary = true, enabled = !state.busy && code.length == 6) { regenerate = true }
        }
        Action("退出登录并清除本机日历和闹钟", secondary = true, enabled = !state.busy) { logout = true }
    }
    if (regenerate) Confirm("更换全部恢复码", "继续后旧恢复码全部失效，新码只显示一次。", { regenerate = false }) {
        vm.perform {
            val r = vm.post("/auth/recovery/regenerate", json("code" to code))
            code = ""; regenerate = false; codes = r.getJSONArray("recovery_codes").strings(); recovery = null
        }
    }
    codes?.let { RecoveryCodes(it) { codes = null } }
    if (logout) Confirm("退出登录", "将删除本应用创建的本地日历与提醒；服务端记录不受影响。", { logout = false }) { logout = false; vm.logout() }
}

@Composable private fun FamilyPanel(vm: AppViewModel, state: AppState) {
    var groupEditor by remember { mutableStateOf<JSONObject?>(null) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var warehouse by remember { mutableStateOf<JSONObject?>(null) }
    var warehouseId by remember { mutableStateOf<String?>(null) }
    var member by remember { mutableStateOf<User?>(null) }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var reset by remember { mutableStateOf<User?>(null) }
    var temporary by remember { mutableStateOf<String?>(null) }
    var removeWarehouse by remember { mutableStateOf<JSONObject?>(null) }
    var rotate by remember { mutableStateOf<FamilyGroup?>(null) }
    LaunchedEffect(Unit) {
        try { users = vm.list("/admin/users").objects().map(User::parse) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { vm.error("无法读取管理员及成员列表") }
    }
    Panel("家庭管理") {
        Action("创建家庭", enabled = !state.busy) { groupId = null; groupEditor = JSONObject() }
        state.groups.forEach { group ->
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Text("${group.timezone} · 分组码 ${group.joinCode ?: "不可见"}")
            Text("早餐 ${group.mealTimes.optString("morning")} · 午餐 ${group.mealTimes.optString("noon")} · 晚餐 ${group.mealTimes.optString("evening")} · 睡前 ${group.mealTimes.optString("bedtime")}")
            Action("改名、时区与餐时", secondary = true, enabled = !state.busy) {
                groupId = group.id; groupEditor = json("name" to group.name, "timezone" to group.timezone,
                    "morning" to group.mealTimes.optString("morning", "08:00"), "noon" to group.mealTimes.optString("noon", "12:00"),
                    "evening" to group.mealTimes.optString("evening", "18:00"), "bedtime" to group.mealTimes.optString("bedtime", "21:30"))
            }
            Action("更换分组码", secondary = true, enabled = !state.busy) { rotate = group }
        }
    }
    Panel("当前家庭成员") {
        state.members.forEach { u ->
            Text("${u.username} · ${if (u.elderly) "老人模式" else "普通模式"}")
            Action("修改老人模式 / 转组", secondary = true, enabled = !state.busy) { member = u }
        }
    }
    Panel("当前家庭药箱") {
        Action("新建药箱", enabled = state.groupId != null && !state.busy) { warehouseId = null; warehouse = json("name" to "") }
        state.warehouses.forEach { w ->
            Text(w.getString("name"))
            Action("改名", secondary = true, enabled = !state.busy) { warehouseId = w.getString("id"); warehouse = json("name" to w.getString("name")) }
            Action("删除空药箱", secondary = true, enabled = !state.busy) { removeWarehouse = w }
        }
    }
    Panel("管理员安全重置") {
        Text("仅能重置其他管理员。会撤销其会话和验证器，生成 7 天有效的一次性密码。", color = Red)
        users.filter { it.admin && it.id != state.user?.id }.forEach { u ->
            Action("重置 ${u.username}", secondary = true, enabled = !state.busy) { reset = u }
        }
    }
    groupEditor?.let { initial ->
        val seed = JSONObject(initial.toString()).apply {
            if (!has("timezone")) put("timezone", "Asia/Shanghai")
            listOf("morning" to "08:00", "noon" to "12:00", "evening" to "18:00", "bedtime" to "21:30").forEach { (k,v) -> if (!has(k)) put(k,v) }
        }
        EditForm("家庭与餐时", listOf(FormField("name", "家庭名称"), FormField("timezone", "IANA 时区"),
            FormField("morning", "早餐 HH:mm"), FormField("noon", "午餐 HH:mm"), FormField("evening", "晚餐 HH:mm"), FormField("bedtime", "睡前 HH:mm")),
            seed, state.busy, { groupEditor = null }) { form ->
            val body = json("name" to form.getString("name"), "timezone" to form.getString("timezone"),
                "meal_times" to json("morning" to form.getString("morning"), "noon" to form.getString("noon"),
                    "evening" to form.getString("evening"), "bedtime" to form.getString("bedtime")))
            vm.save(if (groupId == null) "POST" else "PUT", "/groups" + (groupId?.let { "/$it" } ?: ""), body) { groupEditor = null }
        }
    }
    warehouse?.let { EditForm("药箱名称", listOf(FormField("name", "名称")), it, state.busy, { warehouse = null }) { body ->
        vm.save(if (warehouseId == null) "POST" else "PUT", "/warehouses" + (warehouseId?.let { "/$it" } ?: ""), body) { warehouse = null }
    } }
    member?.let { u -> EditForm("修改 ${u.username}", listOf(FormField("is_elderly", "老人模式", "bool"),
        FormField("group_id", "所属家庭（已有计划时不能跨家庭）", "choice", listOf("" to "不分组") + state.groups.map { it.id to it.name })),
        json("is_elderly" to u.elderly, "group_id" to u.groupId), state.busy, { member = null }) { body ->
        if (body.optString("group_id").isBlank()) body.put("group_id", JSONObject.NULL)
        vm.save("PATCH", "/members/${u.id}", body) { member = null; vm.refresh() }
    } }
    removeWarehouse?.let { w -> Confirm("删除空药箱", "删除 ${w.getString("name")}？有过库存记录的药箱不能删除，请改名。", { removeWarehouse = null }) {
        vm.save("DELETE", "/warehouses/${w.getString("id")}") { removeWarehouse = null }
    } }
    rotate?.let { g -> Confirm("更换家庭分组码", "${g.name} 的旧分组码将立即失效。", { rotate = null }) {
        vm.save("POST", "/groups/${g.id}/rotate-code") { rotate = null; vm.notice("已生成新分组码，请在家庭管理中查看") }
    } }
    reset?.let { u -> Confirm("重置管理员", "重置 ${u.username} 的所有登录凭据？仅显示一次临时密码。", { reset = null }) {
        vm.perform { temporary = vm.post("/admin/users/${u.id}/reset").getString("temporary_password"); reset = null }
    } }
    temporary?.let { secret -> Modal("一次性密码（7 天有效）", { temporary = null }) {
        Text("请安全地交给目标管理员，首次登录后必须重新绑定验证器。")
        SelectionContainer { Text(secret) }
        Action("已记下，关闭") { temporary = null }
    } }
}

private val aiFields = listOf(
    FormField("provider", "提供商", "choice", listOf("custom" to "其他兼容接口", "deepseek" to "DeepSeek")),
    FormField("base_url", "HTTPS 根地址（切换地址需填写新密钥）"), FormField("model", "模型"),
    FormField("llm_key", "新 LLM 密钥（留空保留）", "secret"), FormField("tavily_key", "新 Tavily 密钥（留空保留）", "secret"),
    FormField("search_mode", "检索方式", "choice", listOf("external" to "Tavily 外部检索", "builtin" to "提供商内置检索")),
    FormField("native_search_api", "内置检索协议", "choice", listOf("responses" to "Responses", "chat_completions" to "Chat Completions", "anthropic" to "Anthropic")),
    FormField("query_mode", "药品查询方式", "choice", listOf("tools" to "模型工具循环", "prefetch" to "先检索后整理")),
    FormField("output_mode", "输出格式", "choice", listOf("prompt" to "提示词 JSON", "json_schema" to "严格 JSON Schema")),
    FormField("duckduckgo_enabled", "补充 DuckDuckGo（内置检索时停用）", "bool"),
    FormField("lookup_daily_limit", "家庭每日查询额度", "int"), FormField("chat_daily_limit", "个人每日对话额度", "int"),
    FormField("max_llm_calls", "单次模型调用上限（3–6）", "int"), FormField("max_tool_calls", "单次工具调用上限（1–5）", "int"),
    FormField("max_tokens", "输出 Token 上限（256–8192）", "int"), FormField("timeout_seconds", "超时秒数（10–120）", "int"),
    FormField("extra_body_text", "额外模型参数 JSON（不可覆盖消息、工具或模型）", "multiline"),
    FormField("native_search_body_text", "内置检索额外参数 JSON", "multiline"),
)
@Composable private fun AiSettingsPanel(vm: AppViewModel, state: AppState) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var edit by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var queryResult by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(Unit) {
        try { data = vm.get("/ai/settings") }
        catch (e: CancellationException) { throw e } catch (_: Exception) { vm.error("AI 配置读取失败") }
    }
    Panel("AI 提供商与检索") {
        val settings = data?.optJSONObject("settings")
        if (settings == null) {
            Text("尚未读取配置")
            Action("重新读取") { vm.perform { data = vm.get("/ai/settings") } }
        } else {
            Text("${settings.optString("provider")} · ${settings.optString("model")}")
            Text(settings.optString("base_url"))
            Text("LLM 密钥：${if (data?.optBoolean("has_llm_key") == true) "已保存" else "未保存"} · Tavily：${if (data?.optBoolean("has_tavily_key") == true) "已保存" else "未保存"}")
            Text("搜索：${settings.optString("search_mode")} · ${settings.optString("native_search_api")}")
            Action("编辑完整 AI 配置", enabled = !state.busy) { edit = true }
            Action("读取此接口的模型列表", secondary = true, enabled = !state.busy) { vm.perform {
                models = vm.post("/ai/models", json("settings" to settings)).getJSONArray("models").objects().map { it.getString("id") to it.getString("name") }
            } }
            if (models.isNotEmpty()) Picker("选择模型并保存", settings.getString("model"), models, { id ->
                vm.perform {
                    vm.postSettings(JSONObject(settings.toString()).put("model", id))
                    data = vm.get("/ai/settings")
                }
            }, !state.busy)
            Action("检测内置检索能力", secondary = true, enabled = !state.busy) { vm.perform {
                val probe = vm.post("/ai/search-probe", json("settings" to settings))
                result = probe.getString("message")
                val changed = JSONObject(settings.toString()).put("search_mode", probe.getString("search_mode"))
                probe.nullable("native_search_api")?.let { changed.put("native_search_api", it) }
                if (probe.optBoolean("supported")) changed.put("duckduckgo_enabled", false)
                data = JSONObject(data.toString()).put("settings", changed)
                result += "\n检测结果尚未保存，请点击下方保存。"
            } }
            Action("保存当前检索 / 模型选择", secondary = true, enabled = !state.busy) { vm.perform {
                vm.postSettings(settings); data = vm.get("/ai/settings"); result = "已保存"
            } }
            Action("运行接口能力探针", secondary = true, enabled = !state.busy) { vm.perform { result = vm.post("/ai/probe").toString(2) } }
            if (result.isNotBlank()) Text(result)
        }
    }
    Panel("测试药品查询") {
        Field("测试查询", query, { query = it })
        Action("开始测试（使用当前已保存配置）", enabled = query.isNotBlank() && job?.isActive != true) {
            job = scope.launchSafely(vm) {
                com.homemeds.app.net.AiAgent.runLookup(query.take(500), state.groupId) { event, value ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (event == "progress") result = value.toString()
                        if (event == "result") queryResult = value as JSONObject
                    }
                }
            }
        }
        if (job?.isActive == true) Action("取消测试", secondary = true) { job?.cancel() }
        queryResult?.let { Text("待人工核对：\n${it.toString(2)}"); Sources(parseSources(it.opt("sources"))) }
    }
    if (edit) data?.getJSONObject("settings")?.let { settings ->
        val seed = JSONObject(settings.toString()).put("extra_body_text", settings.getJSONObject("extra_body").toString(2))
            .put("native_search_body_text", settings.getJSONObject("native_search_body").toString(2))
        EditForm("AI 配置", aiFields, seed, state.busy, { edit = false }) { body ->
            val llm = body.optString("llm_key").trim()
            val tavily = body.optString("tavily_key").trim()
            body.remove("llm_key"); body.remove("tavily_key")
            body.put("extra_body", JSONObject(body.getString("extra_body_text")))
            body.put("native_search_body", JSONObject(body.getString("native_search_body_text")))
            body.remove("extra_body_text"); body.remove("native_search_body_text")
            vm.perform {
                val payload = json("settings" to body).apply { if (llm.isNotEmpty()) put("llm_key", llm); if (tavily.isNotEmpty()) put("tavily_key", tavily) }
                vm.mutate("PUT", "/ai/settings", payload)
                data = vm.get("/ai/settings"); edit = false
            }
        }
    }
}

private suspend fun AppViewModel.postSettings(settings: JSONObject) { mutate("PUT", "/ai/settings", json("settings" to settings)) }
private fun kotlinx.coroutines.CoroutineScope.launchSafely(vm: AppViewModel, block: suspend () -> Unit) = launch {
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { vm.error(e.message ?: "请求失败") }
}

@Composable private fun LogsPanel(vm: AppViewModel, state: AppState) {
    var source by remember { mutableStateOf("audit") }
    var user by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loadedFilter by remember { mutableStateOf("") }
    fun filter(): String = listOf("source" to source, "user_id" to user, "group_id" to group, "from" to from,
        "to" to to, "outcome" to outcome, "action" to action).filter { it.second.isNotBlank() }
        .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
    fun load(next: Boolean) {
        val query = if (next) loadedFilter else filter()
        vm.perform {
            val page = vm.get("/admin/logs?$query" + if (next && cursor != null) "&cursor=${URLEncoder.encode(cursor, "UTF-8")}" else "")
            rows = if (next) rows + page.getJSONArray("items").objects() else page.getJSONArray("items").objects()
            loadedFilter = query; cursor = page.nullable("next_cursor")
        }
    }
    LaunchedEffect(Unit) {
        try { users = vm.list("/admin/users").objects().map(User::parse) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { vm.error("读取日志用户筛选失败") }
        load(false)
    }
    Panel("全局使用日志") {
        Picker("日志来源", source, listOf("audit" to "操作审计", "lookup" to "AI 查询", "usage" to "AI 用量", "dose" to "服药记录", "stock" to "库存操作"), { source = it })
        Picker("用户", user, listOf("" to "全部用户") + users.map { it.id to it.username }, { user = it })
        Picker("家庭", group, listOf("" to "全部家庭") + state.groups.map { it.id to it.name }, { group = it })
        Field("开始日期 YYYY-MM-DD（可空）", from, { from = it })
        Field("结束日期 YYYY-MM-DD（可空）", to, { to = it })
        Picker("结果", outcome, listOf("" to "全部结果", "success" to "成功", "failure" to "失败"), { outcome = it })
        Picker("操作类型", action, listOf("" to "全部操作") + logActions.toList(), { action = it })
        Action("按当前条件查询", enabled = !state.busy) { load(false) }
        Text("按时间倒序，每页 30 条。查询后才能加载下一页。", color = Muted)
    }
    rows.forEach { r ->
        Panel(logActions[r.optString("action")] ?: r.optString("action")) {
            Text("${r.optString("created_at")} · ${r.nullable("outcome")?.let { if (it == "success") "成功" else "失败" } ?: "用量统计"}")
            Text("${r.nullable("actor_name") ?: "未登录"} · ${r.nullable("group_name") ?: "全局"}")
            r.nullable("subject_name")?.let { Text("对象：$it") }
            r.nullable("detail")?.let { Text(it) }
            r.nullable("http_status")?.let { Text("HTTP $it") }
        }
    }
    if (rows.isEmpty()) Text("当前条件暂无日志")
    if (cursor != null) Action("加载下一页", secondary = true, enabled = !state.busy && filter() == loadedFilter) { load(true) }
}

val logActions = linkedMapOf(
    "auth.register" to "注册账号", "auth.login" to "登录", "auth.logout" to "退出登录",
    "auth.totp.setup" to "开始绑定验证器", "auth.totp.bind" to "绑定验证器",
    "auth.passkey.options" to "开始通行密钥登录", "auth.passkey.login" to "通行密钥登录",
    "auth.passkey.register.options" to "开始绑定通行密钥", "auth.passkey.register" to "绑定通行密钥",
    "auth.recovery.options" to "验证恢复码操作", "auth.recovery.regenerate" to "重新生成恢复码",
    "admin.reset" to "重置管理员", "group.create" to "创建家庭", "group.update" to "修改家庭", "group.rotate" to "更换分组码",
    "member.update" to "修改成员", "warehouse.create" to "创建药箱", "warehouse.update" to "修改药箱", "warehouse.delete" to "删除药箱",
    "product.create" to "创建药品", "product.update" to "修改药品", "stock.create" to "药品入库", "stock.change" to "调整库存", "stock.update" to "修改药盒资料",
    "stock.in" to "入库", "stock.out" to "出库", "stock.move" to "移动药盒", "stock.discard" to "丢弃药盒", "stock.dose" to "服药扣库存", "stock.undo_dose" to "撤销服药恢复库存",
    "plan.create" to "创建计划", "plan.update" to "修改计划", "plan.pause" to "暂停计划", "dose.create" to "记录服药或跳过",
    "dose.undo" to "撤销服药记录", "dose.record" to "服药记录", "ai.settings.read" to "查看 AI 配置", "ai.settings.update" to "修改 AI 配置",
    "ai.probe" to "检测接口能力", "ai.lookup" to "AI 药品查询", "ai.chat" to "AI 计划对话", "ai.models" to "获取模型列表",
    "ai.app.session" to "手机端 AI 会话", "ai.app.finish" to "手机端 AI 结果", "ai.search.probe" to "检测内置搜索",
    "usage.lookup" to "家庭药品查询用量", "usage.chat" to "个人对话用量", "push.subscribe" to "开启设备提醒",
    "push.unsubscribe" to "关闭设备提醒", "logs.view" to "查看使用日志", "request.other" to "其他写入请求",
)
