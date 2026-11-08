package com.homemeds.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.homemeds.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class SettingsEntry(val id: String, val title: String, val admin: Boolean = false)
internal val settingsEntries = listOf(
    SettingsEntry("assistant", "助手偏好"), SettingsEntry("calendar", "日历与提醒"),
    SettingsEntry("permissions", "权限与后台"), SettingsEntry("account", "账号与安全"),
    SettingsEntry("family", "家庭管理", admin = true), SettingsEntry("ai", "AI 配置", admin = true),
    SettingsEntry("logs", "使用日志", admin = true),
)

/** Settings tab: a short menu whose rows open full sub-pages under `settings/<id>`. */
fun NavGraphBuilder.settingsGraph(nav: NavController, vm: AppViewModel, state: AppState) {
    navigation(startDestination = "settings/menu", route = "settings") {
        composable("settings/menu") { SettingsMenu(vm, state) { nav.navigate("settings/$it") { launchSingleTop = true } } }
        settingsEntries.forEach { entry ->
            composable("settings/${entry.id}") {
                SettingsSubPage(entry.title, back = { nav.popBackStack() }) {
                    if (entry.admin && state.user?.admin != true) Text("仅管理员可用。", color = Muted)
                    else when (entry.id) {
                        "assistant" -> AssistantPreferencesPanel(state, vm::setAssistantTarget, vm::setAssistantPhone)
                        "calendar" -> CalendarPage(vm, state)
                        "permissions" -> PermissionsPage(vm, state)
                        "account" -> AccountPage(vm, state)
                        "family" -> FamilyPanel(vm, state)
                        "ai" -> AiSettingsPanel(vm, state)
                        "logs" -> LogsPanel(vm, state)
                    }
                }
            }
        }
    }
}

@Composable internal fun SettingsSubPage(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Page { SubPage(title, back); content() }
}

private fun formatSync(millis: Long?) = millis?.let {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
}

@Composable internal fun SettingsMenu(vm: AppViewModel, state: AppState, open: (String) -> Unit) {
    val admin = state.user?.admin == true
    val target = state.members.find { it.id == assistantTarget(state) }?.username ?: "未选择"
    val subtitles = mapOf(
        "assistant" to "计划对象：$target · ${if (state.assistantPhone) "手机运行（Worker 代理）" else "服务端连接"}",
        "calendar" to "上次同步 ${formatSync(vm.prefs.lastSync) ?: "尚未同步"} · 日历 ${vm.prefs.events} 条",
        "permissions" to "日历、通知、精确闹钟与电池优化",
        "account" to "${state.user?.username.orEmpty()} · ${if (admin) "管理员" else "家庭成员"}",
        "family" to "${state.groups.size} 个家庭 · 成员与药箱",
        "ai" to "模型、检索与测试查询",
        "logs" to "操作审计与 AI 用量",
    )
    Page {
        RowGroup {
            settingsEntries.filter { admin || !it.admin }.forEachIndexed { i, entry ->
                if (i > 0) RowDivider()
                SettingRow(entry.title, subtitles[entry.id], onClick = { open(entry.id) })
            }
        }
    }
}

@Composable internal fun AssistantPreferencesPanel(state: AppState, target: (String) -> Unit, phone: (Boolean) -> Unit) {
    Panel {
        Picker("计划对象", assistantTarget(state), state.members.map { it.id to it.username }, target,
            enabled = !state.busy && state.members.isNotEmpty())
        if (state.members.isEmpty()) Text("请先选择家庭并添加成员。", color = Muted)
        Check("手机运行 AI 循环（Worker 代理）", state.assistantPhone, phone, enabled = !state.busy)
        Text("关闭后纯文字对话使用服务端连接；带图片时仍由手机运行循环，经 Worker 代理调用支持视觉的模型。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Re-reads reminder status whenever the screen resumes, e.g. after returning from a system settings page. */
@Composable private fun rememberSyncStatus(vm: AppViewModel, state: AppState): Pair<JSONObject, () -> Unit> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(Sync.status(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { revision++; vm.syncDebounced() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, state.notice, state.busy) { status = Sync.status(context) }
    return status to { revision++ }
}

@Composable private fun CalendarPage(vm: AppViewModel, state: AppState) {
    val (_, bump) = rememberSyncStatus(vm, state)
    var members by remember { mutableStateOf(vm.prefs.members.toSet()) }
    var calendarAlerts by remember { mutableStateOf(vm.prefs.calendarAlerts) }
    var appAlerts by remember { mutableStateOf(vm.prefs.appNotifications) }
    Text("所选成员的定时计划会写入手机本地「赛博药箱」日历，每条日程 15 分钟并按 RRULE 重复。",
        color = Muted, style = MaterialTheme.typography.bodySmall)
    Panel {
        state.members.forEach { user -> Toggle("同步 ${user.username}", user.id in members, {
            members = if (it) members + user.id else members - user.id
            vm.prefs.members = members.toList(); vm.syncDebounced()
        }) }
        if (state.members.isNotEmpty()) HorizontalDivider()
        Toggle("日历到点响铃", calendarAlerts, { calendarAlerts = it; vm.prefs.calendarAlerts = it; vm.syncDebounced() })
        Toggle("应用到点通知（本人 + 家人超时）", appAlerts, { appAlerts = it; vm.prefs.appNotifications = it; vm.syncDebounced() })
        Text("本人按时提醒；非老人用户可接收所选家人超 30 分钟未记录的提醒。通知前联网核对，离线时明确标记未核对。",
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("上次同步：" + formatSync(vm.prefs.lastSync).orEmpty(), style = MaterialTheme.typography.bodySmall)
            Text("日历 ${vm.prefs.events} 条 · 未来 48 小时提醒 ${vm.prefs.alarms} 个", style = MaterialTheme.typography.bodySmall)
        }
        Action("立即同步", enabled = !state.busy) { vm.syncNow(); bump() }
    }
    vm.prefs.lastError?.let { Text(it, color = Red) }
    Text("后台每 6 小时同步一次；部分手机需手动允许自启动和后台运行。", color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun PermissionRow(title: String, granted: Boolean, grantedLabel: String = "已允许",
    deniedLabel: String = "未允许", open: (() -> Unit)?, more: List<MenuAction> = emptyList()) {
    SettingRow(title, if (granted) grantedLabel else deniedLabel, trailing = {
        if (granted || open == null) StatusChip(if (granted) grantedLabel else deniedLabel, if (granted) Green else Amber)
        else Action("去开启", onClick = open)
        MoreMenu(more, description = "$title 更多操作")
    })
}

@Composable private fun PermissionsPage(vm: AppViewModel, state: AppState) {
    val context = LocalContext.current
    val calendarPermission = LocalCalendarPermission.current
    val notifications = LocalNotificationPermission.current
    val (status, _) = rememberSyncStatus(vm, state)
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { vm.error("手机不支持此设置入口，请在系统设置中手动打开") } }
    val battery = !status.optBoolean("battery_optimized", true)
    RowGroup {
        PermissionRow("日历权限", status.optBoolean("calendar_permission"), open = calendarPermission)
        RowDivider()
        PermissionRow("通知权限", status.optBoolean("notification_permission"), open = notifications,
            more = listOf(MenuAction("系统通知设置") {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }))
        RowDivider()
        PermissionRow("精确闹钟", status.optBoolean("exact_alarm"), deniedLabel = "未允许，将尽力提醒",
            open = if (Build.VERSION.SDK_INT >= 31) {
                { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
            } else null)
        RowDivider()
        PermissionRow("电池优化", battery, grantedLabel = "不受限制", deniedLabel = "建议设为不限制",
            open = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
    }
    Text("后台每 6 小时同步一次；部分手机需手动允许自启动和后台运行。", color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun AccountPage(vm: AppViewModel, state: AppState) {
    val admin = state.user?.admin == true
    var recovery by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }
    var codes by remember { mutableStateOf<List<String>?>(null) }
    var verify by remember { mutableStateOf(false) }
    var regenerate by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    LaunchedEffect(admin) {
        if (admin) try { recovery = vm.get("/auth/recovery") }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    RowGroup {
        SettingRow(state.user?.username.orEmpty(), if (admin) "管理员" else "家庭成员", trailing = {})
        if (admin) {
            RowDivider()
            SettingRow("验证器与恢复码", recovery?.let { r ->
                "验证器${if (r.optBoolean("totp")) "已绑定" else "未绑定"} · 恢复码剩余 ${r.optInt("remaining")} 个 · 通行密钥 ${r.optInt("passkeys")} 个"
            } ?: "尚未读取", trailing = {
                TextButton({ vm.perform { recovery = vm.get("/auth/recovery") } }, enabled = !state.busy) { Text("刷新") }
            })
        }
    }
    if (admin) Text("服务端只保存恢复码摘要，不能再次查看原码。可验证身份后重新生成。", color = Muted, style = MaterialTheme.typography.bodySmall)
    ActionRow {
        if (admin) Action("重新生成恢复码", secondary = true, enabled = !state.busy) { code = ""; verify = true }
        Action("退出登录并清除本机日历和闹钟", secondary = true, enabled = !state.busy) { logout = true }
    }
    if (verify) Modal("重新生成恢复码", { verify = false; code = "" }) {
        Text("请输入验证器当前的 6 位验证码。继续后旧恢复码全部失效。", style = MaterialTheme.typography.bodySmall)
        Field("验证器 6 位验证码", code, { code = it.filter(Char::isDigit).take(6) }, secret = true)
        ActionRow {
            Action("取消", secondary = true) { verify = false; code = "" }
            Action("重新生成恢复码", enabled = !state.busy && code.length == 6) { regenerate = true }
        }
    }
    if (regenerate) Confirm("更换全部恢复码", "继续后旧恢复码全部失效，新码只显示一次。", { regenerate = false }) {
        vm.perform {
            val r = vm.post("/auth/recovery/regenerate", json("code" to code))
            code = ""; regenerate = false; verify = false; codes = r.getJSONArray("recovery_codes").strings(); recovery = null
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
    var resetPicker by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf<User?>(null) }
    var temporary by remember { mutableStateOf<String?>(null) }
    var removeWarehouse by remember { mutableStateOf<JSONObject?>(null) }
    var rotate by remember { mutableStateOf<FamilyGroup?>(null) }
    LaunchedEffect(Unit) {
        try { users = vm.list("/admin/users").objects().map(User::parse) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { vm.error("无法读取管理员及成员列表") }
    }
    SectionHeader("家庭") { TextButton({ groupId = null; groupEditor = JSONObject() }, enabled = !state.busy) { Text("创建家庭") } }
    RowGroup {
        state.groups.forEachIndexed { i, group ->
            if (i > 0) RowDivider()
            SettingRow(group.name, "${group.timezone} · 分组码 ${group.joinCode ?: "不可见"}\n" +
                "早餐 ${group.mealTimes.optString("morning")} · 午餐 ${group.mealTimes.optString("noon")} · 晚餐 ${group.mealTimes.optString("evening")} · 睡前 ${group.mealTimes.optString("bedtime")}",
                subtitleLines = 4, trailing = { MoreMenu(listOf(
                    MenuAction("改名、时区与餐时", !state.busy) {
                        groupId = group.id; groupEditor = json("name" to group.name, "timezone" to group.timezone,
                            "morning" to group.mealTimes.optString("morning", "08:00"), "noon" to group.mealTimes.optString("noon", "12:00"),
                            "evening" to group.mealTimes.optString("evening", "18:00"), "bedtime" to group.mealTimes.optString("bedtime", "21:30"))
                    },
                    MenuAction("更换分组码", !state.busy) { rotate = group },
                ), description = "${group.name} 更多操作") })
        }
        if (state.groups.isEmpty()) Text("还没有家庭", Modifier.padding(14.dp), color = Muted)
    }
    SectionHeader("当前家庭成员")
    RowGroup {
        state.members.forEachIndexed { i, u ->
            if (i > 0) RowDivider()
            SettingRow(u.username, if (u.elderly) "老人模式" else "普通模式", trailing = {
                MoreMenu(listOf(MenuAction("修改老人模式 / 转组", !state.busy) { member = u }), description = "${u.username} 更多操作")
            })
        }
        if (state.members.isEmpty()) Text("当前家庭暂无成员", Modifier.padding(14.dp), color = Muted)
    }
    SectionHeader("当前家庭药箱") {
        TextButton({ warehouseId = null; warehouse = json("name" to "") }, enabled = state.groupId != null && !state.busy) { Text("新建药箱") }
    }
    RowGroup {
        state.warehouses.forEachIndexed { i, w ->
            if (i > 0) RowDivider()
            SettingRow(w.getString("name"), trailing = { MoreMenu(listOf(
                MenuAction("改名", !state.busy) { warehouseId = w.getString("id"); warehouse = json("name" to w.getString("name")) },
                MenuAction("删除空药箱", !state.busy, danger = true) { removeWarehouse = w },
            ), description = "${w.getString("name")} 更多操作") })
        }
        if (state.warehouses.isEmpty()) Text("当前家庭暂无药箱", Modifier.padding(14.dp), color = Muted)
    }
    RowGroup { SettingRow("管理员安全重置", "撤销其他管理员的会话和验证器", onClick = { resetPicker = true }) }
    if (resetPicker) Modal("管理员安全重置", { resetPicker = false }) {
        Text("仅能重置其他管理员。会撤销其会话和验证器，生成 7 天有效的一次性密码。", color = Red)
        val admins = users.filter { it.admin && it.id != state.user?.id }
        if (admins.isEmpty()) Text("没有其他管理员。", color = Muted)
        ActionRow { admins.forEach { u -> Action("重置 ${u.username}", secondary = true, enabled = !state.busy) { resetPicker = false; reset = u } } }
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
            vm.save(if (groupId == null) "POST" else "PUT", "/groups" + (groupId?.let { "/${pathSegment(it)}" } ?: ""), body) { groupEditor = null }
        }
    }
    warehouse?.let { EditForm("药箱名称", listOf(FormField("name", "名称")), it, state.busy, { warehouse = null }) { body ->
        vm.save(if (warehouseId == null) "POST" else "PUT", "/warehouses" + (warehouseId?.let { "/${pathSegment(it)}" } ?: ""), body) { warehouse = null }
    } }
    member?.let { u -> EditForm("修改 ${u.username}", listOf(FormField("is_elderly", "老人模式", "bool"),
        FormField("group_id", "所属家庭（已有计划时不能跨家庭）", "choice", listOf("" to "不分组") + state.groups.map { it.id to it.name })),
        json("is_elderly" to u.elderly, "group_id" to u.groupId), state.busy, { member = null }) { body ->
        if (body.optString("group_id").isBlank()) body.put("group_id", JSONObject.NULL)
        vm.save("PATCH", "/members/${pathSegment(u.id)}", body) { member = null }
    } }
    removeWarehouse?.let { w -> Confirm("删除空药箱", "删除 ${w.getString("name")}？有过库存记录的药箱不能删除，请改名。", { removeWarehouse = null }) {
        vm.save("DELETE", "/warehouses/${pathSegment(w.getString("id"))}") { removeWarehouse = null }
    } }
    rotate?.let { g -> Confirm("更换家庭分组码", "${g.name} 的旧分组码将立即失效。", { rotate = null }) {
        vm.save("POST", "/groups/${pathSegment(g.id)}/rotate-code") { rotate = null; vm.notice("已生成新分组码，请在家庭管理中查看") }
    } }
    reset?.let { u -> Confirm("重置管理员", "重置 ${u.username} 的所有登录凭据？仅显示一次临时密码。", { reset = null }) {
        vm.perform { temporary = vm.post("/admin/users/${pathSegment(u.id)}/reset").getString("temporary_password"); reset = null }
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
    var testing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var queryResult by remember { mutableStateOf<JSONObject?>(null) }
    var progress by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(Unit) {
        try { data = vm.get("/ai/settings") }
        catch (e: CancellationException) { throw e } catch (_: Exception) { vm.error("AI 配置读取失败") }
    }
    val settings = data?.optJSONObject("settings")
    if (settings == null) Panel {
        Text("尚未读取配置")
        Action("重新读取") { vm.perform { data = vm.get("/ai/settings") } }
    } else {
        Panel("${settings.optString("provider")} · ${settings.optString("model")}") {
            Text(settings.optString("base_url"), color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("LLM 密钥：${if (data?.optBoolean("has_llm_key") == true) "已保存" else "未保存"} · Tavily：${if (data?.optBoolean("has_tavily_key") == true) "已保存" else "未保存"}",
                style = MaterialTheme.typography.bodySmall)
            Text("搜索：${settings.optString("search_mode")} · ${settings.optString("native_search_api")}", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Action("编辑完整 AI 配置", enabled = !state.busy, modifier = Modifier.weight(1f)) { edit = true }
            MoreMenu(listOf(
                MenuAction("读取此接口的模型列表", !state.busy) { vm.perform {
                    models = vm.post("/ai/models", json("settings" to settings)).getJSONArray("models").objects().map { it.getString("id") to it.getString("name") }
                } },
                MenuAction("检测内置检索能力", !state.busy) { vm.perform {
                    val probe = vm.post("/ai/search-probe", json("settings" to settings))
                    result = probe.getString("message")
                    val changed = JSONObject(settings.toString()).put("search_mode", probe.getString("search_mode"))
                    probe.nullable("native_search_api")?.let { changed.put("native_search_api", it) }
                    if (probe.optBoolean("supported")) changed.put("duckduckgo_enabled", false)
                    data = JSONObject(data.toString()).put("settings", changed)
                    result += "\n检测结果尚未保存，请在 ⋮ 菜单中保存。"
                } },
                MenuAction("保存当前检索 / 模型选择", !state.busy) { vm.perform { data = vm.postSettings(settings); result = "已保存" } },
                MenuAction("运行接口能力探针", !state.busy) { vm.perform { result = vm.post("/ai/probe").toString(2) } },
                MenuAction("测试药品查询") { testing = true },
            ), description = "AI 配置更多操作")
        }
        if (models.isNotEmpty()) Picker("选择模型并保存", settings.getString("model"), models, { id ->
            vm.perform { data = vm.postSettings(JSONObject(settings.toString()).put("model", id)) }
        }, !state.busy)
        if (result.isNotBlank()) Panel { SelectionContainer { Text(result, style = MaterialTheme.typography.bodySmall) } }
    }
    if (testing) Modal("测试药品查询", { testing = false }) {
        Field("测试查询", query, { query = it })
        ActionRow {
            Action("开始测试（使用当前已保存配置）", enabled = query.isNotBlank() && job?.isActive != true) {
                queryResult = null
                job = scope.launchSafely(vm) {
                    try {
                        com.homemeds.app.net.AiAgent.runLookup(query.take(500), state.groupId) { event, value ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (event == "progress") progress = value.toString()
                                if (event == "result") queryResult = value as JSONObject
                            }
                        }
                    } finally { progress = "" }
                }
            }
            if (job?.isActive == true) Action("取消测试", secondary = true) { job?.cancel() }
        }
        if (progress.isNotBlank()) Text(progress, color = Muted, style = MaterialTheme.typography.bodySmall)
        queryResult?.let {
            Text("待人工核对：", color = Red)
            SelectionContainer { Text(it.toString(2), style = MaterialTheme.typography.bodySmall) }
            Sources(parseSources(it.opt("sources")))
        }
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
                val response = vm.mutate("PUT", "/ai/settings", payload)
                data = if (response.has("settings")) response else vm.get("/ai/settings")
                edit = false
            }
        }
    }
}
private suspend fun AppViewModel.postSettings(settings: JSONObject): JSONObject {
    val response = mutate("PUT", "/ai/settings", json("settings" to settings))
    return if (response.has("settings")) response else get("/ai/settings")
}
private fun kotlinx.coroutines.CoroutineScope.launchSafely(vm: AppViewModel, block: suspend () -> Unit) = launch {
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { vm.error(e.message ?: "请求失败") }
}

private val logSources = listOf("audit" to "操作审计", "lookup" to "AI 查询", "usage" to "AI 用量", "dose" to "服药记录", "stock" to "库存操作")

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
    var filters by remember { mutableStateOf(false) }
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
    val summary = listOfNotNull(logSources.toMap()[source], users.find { it.id == user }?.username, state.groups.find { it.id == group }?.name,
        from.ifBlank { null }?.let { "自 $it" }, to.ifBlank { null }?.let { "至 $it" },
        when (outcome) { "success" -> "成功"; "failure" -> "失败"; else -> null }, logActions[action]).joinToString(" · ")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(summary, fontWeight = FontWeight.SemiBold)
            Text("按时间倒序，每页 30 条", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Action("筛选", secondary = true, enabled = !state.busy) { filters = true }
    }
    RowGroup {
        rows.forEachIndexed { i, r ->
            if (i > 0) RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(logActions[r.optString("action")] ?: r.optString("action"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    when (r.nullable("outcome")) {
                        null -> StatusChip("用量统计", Muted)
                        "success" -> StatusChip("成功")
                        else -> StatusChip("失败", Red)
                    }
                }
                Text("${r.optString("created_at")} · ${r.nullable("actor_name") ?: "未登录"} · ${r.nullable("group_name") ?: "全局"}",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                val extra = listOfNotNull(r.nullable("subject_name")?.let { "对象：$it" }, r.nullable("detail"), r.nullable("http_status")?.let { "HTTP $it" })
                if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (rows.isEmpty()) Text("当前条件暂无日志", Modifier.padding(14.dp), color = Muted)
    }
    if (cursor != null) Action("加载下一页", secondary = true, enabled = !state.busy && filter() == loadedFilter) { load(true) }
    if (filters) Modal("筛选使用日志", { filters = false }) {
        Picker("日志来源", source, logSources, { source = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("用户", user, listOf("" to "全部用户") + users.map { it.id to it.username }, { user = it }, modifier = Modifier.weight(1f))
            Picker("家庭", group, listOf("" to "全部家庭") + state.groups.map { it.id to it.name }, { group = it }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("开始日期 YYYY-MM-DD（可空）", from, { from = it }, Modifier.weight(1f))
            Field("结束日期 YYYY-MM-DD（可空）", to, { to = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("结果", outcome, listOf("" to "全部结果", "success" to "成功", "failure" to "失败"), { outcome = it }, modifier = Modifier.weight(1f))
            Picker("操作类型", action, listOf("" to "全部操作") + logActions.toList(), { action = it }, modifier = Modifier.weight(1f))
        }
        Action("按当前条件查询", enabled = !state.busy) { filters = false; load(false) }
    }
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
