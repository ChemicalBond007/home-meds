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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    val route = back?.destination?.route ?: "home"
    val speak = LocalSpeak.current
    var dose by remember(state.user?.id, state.groupId) { mutableStateOf<DoseRequest?>(null) }
    val screens = listOf("home" to "今日", "inventory" to "药箱", "plans" to "计划", "chat" to "助手", "settings" to "设置")
    val icons = listOf(Icons.Default.Home, Icons.Default.Inventory2, Icons.Default.Schedule, Icons.AutoMirrored.Filled.Chat, Icons.Default.Settings)
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
        if (state.user != null && !state.bindingOnly) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).statusBarsPadding()) {
            Row {
                Text("家药箱", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Action("朗读", secondary = true) { speak("今天 ${state.today}。" + state.todos.filter { it.plan.userId == state.user.id }.joinToString("。") { "${it.time} ${it.plan.medicineName} ${Texts.amount(it.plan.amount)}${it.plan.unit}，${stateLabel(it.state)}" }) }
            }
            if (state.user.admin) Picker("当前家庭", state.groupId, state.groups.map { it.id to it.name }, vm::selectGroup, !state.busy)
            else Text("${state.groups.find { it.id == state.groupId }?.name.orEmpty()} · ${state.user.username}", color = Muted)
        }
    }, bottomBar = {
        if (state.user != null && !state.bindingOnly) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            screens.forEachIndexed { i, (id, title) ->
                if (!(state.user.elderly && i in 2..3)) NavigationBarItem(route == id,
                    onClick = { nav.navigate(id) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                    icon = { Icon(icons[i], title) }, label = { Text(title) }, modifier = Modifier.heightIn(min = touchHeight))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green)
            state.error?.let { Row(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(it, Modifier.weight(1f), color = Red)
                Action("关闭", secondary = true) { vm.error(null) }
            } }
            state.notice?.let { Row(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(it, Modifier.weight(1f)); Action("知道了", secondary = true) { vm.notice(null) }
            } }
            if (state.loading) Text("正在恢复登录…", Modifier.padding(24.dp))
            else if (state.user == null || state.bindingOnly) AuthScreen(vm, state)
            else key(state.user.id, state.groupId) {
                NavHost(nav, "home", modifier = Modifier.weight(1f)) {
                    composable("home") { HomeScreen(vm, state) { dose = it } }
                    composable("inventory") { InventoryScreen(vm, state) }
                    composable("plans") { PlansScreen(vm, state) { dose = it } }
                    composable("chat") { ChatScreen(vm, state) }
                    composable("settings") { SettingsScreen(vm, state) }
                }
            }
        }
    }
    dose?.let { request -> DoseDialog(vm, state, request) { dose = null } }
}
