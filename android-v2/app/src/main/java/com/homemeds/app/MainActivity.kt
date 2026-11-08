package com.homemeds.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.homemeds.app.ui.*
import kotlinx.coroutines.launch

data class NotificationTarget(val plan: String, val date: String, val time: String, val group: String)
data class DoseRequest(val plan: Plan, val date: String, val time: String)
val LocalSpeak = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalCalendarPermission = staticCompositionLocalOf<() -> Unit> { {} }
val LocalNotificationPermission = staticCompositionLocalOf<() -> Unit> { {} }
internal fun appTitle(route: String) = if (route == "chat") "赛博药箱AI助手" else "赛博药箱"

private val appScreens = listOf("home" to "今日", "inventory" to "药箱", "chat" to "助手", "plans" to "计划", "settings" to "设置")

@Composable internal fun AssistantHeader(canClear: Boolean, family: @Composable () -> Unit = {}, clear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(appTitle("chat"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        family()
        IconButton(clear, Modifier.size(touchHeight), enabled = canClear) {
            Icon(Icons.Default.DeleteSweep, "清空对话历史")
        }
    }
}

/** One-row top bar: title (with a member subtitle), optional family switcher, then icon actions. */
@Composable internal fun AppTopBar(title: String, subtitle: String?, family: @Composable () -> Unit = {},
    speak: () -> Unit, refresh: () -> Unit, refreshEnabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        family()
        IconButton(speak, Modifier.size(touchHeight)) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "朗读今日清单") }
        IconButton(refresh, Modifier.size(touchHeight), enabled = refreshEnabled) { Icon(Icons.Default.Refresh, "刷新今日清单") }
    }
}

@Composable internal fun Banner(text: String, error: Boolean, closeLabel: String, close: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium,
        color = if (error) Color(0xFFFBEAEA) else Color(0xFFEDF2E9)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) Icons.Default.ErrorOutline else Icons.Default.Info, null, tint = if (error) Red else Green)
            Text(text, Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = if (error) Red else Ink)
            IconButton(close, Modifier.size(touchHeight)) { Icon(Icons.Default.Close, closeLabel) }
        }
    }
}

@Composable internal fun AppNavigationBar(route: String, elderly: Boolean, navigate: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        appScreens.filterNot { elderly && it.first in setOf("plans", "chat") }.forEach { (id, title) ->
            val icon = when (id) {
                "home" -> Icons.Default.Home
                "inventory" -> Icons.Default.Inventory2
                "chat" -> Icons.AutoMirrored.Filled.Chat
                "plans" -> Icons.Default.Schedule
                else -> Icons.Default.Settings
            }
            NavigationBarItem(route == id, onClick = { navigate(id) },
                icon = { Icon(icon, title) }, label = { Text(title) }, modifier = Modifier.heightIn(min = touchHeight))
        }
    }
}

class MainActivity : ComponentActivity() {
    private var target by mutableStateOf<NotificationTarget?>(null)
    private lateinit var speech: Speech
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        speech = Speech(this)
        receive(intent)
        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.syncDebounced() }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.syncDebounced() }
            HomeMedsTheme(state.user?.elderly == true) {
                CompositionLocalProvider(
                    LocalSpeak provides { text -> scope.launch { try { speech.speak(text) } catch (e: Exception) { vm.error(e.message) } }; Unit },
                    LocalCalendarPermission provides { calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
                    LocalNotificationPermission provides {
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.syncDebounced()
                    },
                ) { NativeApp(vm, state, target) { target = null } }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent) {
        val plan = intent.getStringExtra(EXTRA_PLAN_ID) ?: return
        target = NotificationTarget(plan, intent.getStringExtra(EXTRA_DATE) ?: return,
            intent.getStringExtra(EXTRA_TIME) ?: return, intent.getStringExtra(EXTRA_GROUP_ID) ?: return)
    }
    override fun onStop() { speech.stop(); super.onStop() }
    override fun onDestroy() { speech.shutdown(); super.onDestroy() }
    companion object {
        const val EXTRA_PLAN_ID = "plan_id"
        const val EXTRA_DATE = "date"
        const val EXTRA_TIME = "time"
        const val EXTRA_GROUP_ID = "group_id"
    }
}

@Composable private fun NativeApp(vm: AppViewModel, state: AppState, target: NotificationTarget?, consumed: () -> Unit) {
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.hierarchy?.mapNotNull { it.route }?.firstOrNull { r -> appScreens.any { it.first == r } } ?: "home"
    val speak = LocalSpeak.current
    var dose by remember(state.user?.id, state.groupId) { mutableStateOf<DoseRequest?>(null) }
    var clearChat by remember(state.user?.id, state.groupId) { mutableStateOf(false) }
    LaunchedEffect(state.user?.elderly) {
        if (state.user?.elderly == true && route in setOf("plans", "chat")) nav.navigate("home") { popUpTo("home") { inclusive = true } }
    }
    LaunchedEffect(target, state.groupId, state.plans, state.busy) {
        if (target == null || state.user == null || state.bindingOnly || state.busy) return@LaunchedEffect
        if (state.groupId != target.group) {
            if (state.user.admin && state.groups.any { it.id == target.group }) vm.selectGroup(target.group)
            else { vm.error("该提醒不属于当前家庭"); consumed() }
            return@LaunchedEffect
        }
        val plan = state.plans.find { it.id == target.plan } ?: run { vm.error("提醒对应的计划已不存在，请刷新今日清单"); consumed(); return@LaunchedEffect }
        if (target.date != state.today || !plan.enabled || target.time !in plan.times || !Schedule.scheduledOn(plan, java.time.LocalDate.parse(target.date))) {
            vm.error("这条提醒已过期或计划已变更，请查看今日清单"); consumed(); return@LaunchedEffect
        }
        val todo = state.todos.find { it.plan.id == plan.id && it.time == target.time }
        if (todo?.state in setOf("taken", "skipped")) vm.notice("本次服药已经记录，请勿重复服药")
        else dose = DoseRequest(plan, target.date, target.time)
        consumed()
    }
    Scaffold(containerColor = Cream, topBar = {
        if (state.user != null && !state.bindingOnly) Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 2.dp)) {
            val family: @Composable () -> Unit = {
                if (state.user.admin) ChipPicker(state.groupId, state.groups.map { it.id to it.name }, vm::selectGroup, !state.busy, "切换家庭")
            }
            if (route == "chat") AssistantHeader(!state.busy, family) { clearChat = true }
            else AppTopBar(appTitle(route),
                if (state.user.admin) null else "${state.groups.find { it.id == state.groupId }?.name.orEmpty()} · ${state.user.username}",
                family, speak = { speak("今天 ${state.today}。" + state.todos.filter { it.plan.userId == state.user.id }.joinToString("。") { "${it.time} ${it.plan.medicineName} ${Texts.amount(it.plan.amount)}${it.plan.unit}，${stateLabel(it.state)}" }) },
                refresh = vm::refresh, refreshEnabled = !state.busy)
        }
    }, bottomBar = {
        if (state.user != null && !state.bindingOnly) AppNavigationBar(route, state.user.elderly) { id ->
            nav.navigate(id) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green)
            state.error?.let { Banner(it, error = route != "chat", closeLabel = "关闭") { vm.error(null) } }
            state.notice?.let { Banner(it, error = false, closeLabel = "知道了") { vm.notice(null) } }
            if (state.loading) Text("正在恢复登录…", Modifier.padding(24.dp))
            else if (state.user == null || state.bindingOnly) AuthScreen(vm, state)
            else key(state.user.id, state.groupId) {
                NavHost(nav, "home", modifier = Modifier.weight(1f)) {
                    composable("home") { HomeScreen(vm, state) { dose = it } }
                    composable("inventory") { InventoryScreen(vm, state) }
                    composable("plans") { PlansScreen(vm, state) { dose = it } }
                    composable("chat") { ChatScreen(vm, state, clearChat) { clearChat = false } }
                    settingsGraph(nav, vm, state)
                }
            }
        }
    }
    dose?.let { request -> DoseDialog(vm, state, request) { dose = null } }
}
