package com.homemeds.app.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.homemeds.app.*
import com.homemeds.app.net.ApiClient
import com.homemeds.app.net.NativeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fixed controls must fit a 360×640dp phone; actions moved into "⋮" must stay reachable. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val store = ViewModelStore()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = CompactFixture.dispatcher()
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        prefs.clear(); prefs.appNotifications = false; prefs.sessionCookie = "session"
        ApiClient.init(context, NativeApi(server.url("/").toString().trimEnd('/'),
            { prefs.sessionCookie }, { prefs.sessionCookie = it }, { prefs.activeGroupId }))
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain(); server.close() }

    private fun model(): AppViewModel {
        val vm = AppViewModel(RuntimeEnvironment.getApplication()).also { store.put("app", it) }
        runBlocking { withTimeout(5000) { vm.state.first { !it.loading } } }
        assertNotNull(vm.state.value.user)
        return vm
    }
    private fun bounds(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInRoot
    private fun sameRow(a: Rect, b: Rect) = a.top < b.bottom && b.top < a.bottom

    // The real shell adds a top bar (~78dp with status bar) and a bottom bar (~80dp) around the 640dp tab content.
    private fun phoneFrame(content: @Composable () -> Unit) {
        compose.setContent { HomeMedsTheme(false) {
            Box(Modifier.width(360.dp).height(482.dp).testTag("frame")) { content() }
        } }
    }

    @Test fun todoCardShowsPrimaryActionAndMenuSideBySideAndMenuHoldsSkip() {
        val plan = parsePlan(CompactFixture.plan("p", "a", "阿司匹林肠溶片", null, listOf("08:00")))
        var skipped = 0
        var recorded = 0
        phoneFrame { TodoCard(Todo(plan, CompactFixture.TODAY, "08:00", "pending", null), false,
            record = { recorded++ }, skip = { skipped++ }, undo = {}, speak = {}) }
        val primary = compose.onNodeWithText("核对并记录服药").assertIsDisplayed()
        val more = compose.onNodeWithContentDescription("更多操作").assertIsDisplayed()
        assertTrue(sameRow(bounds(primary), bounds(more)))
        assertTrue(bounds(more).left >= bounds(primary).right)
        compose.onNodeWithText("这次跳过").assertDoesNotExist()
        more.performClick()
        compose.onNodeWithText("朗读").assertExists()
        compose.onNodeWithText("这次跳过").performClick()
        compose.runOnIdle { assertEquals(1, skipped); assertEquals(0, recorded) }
    }

    @Test fun recordedTodoShowsRecorderAndCompactUndoInsteadOfRecordButton() {
        val plan = parsePlan(CompactFixture.plan("p", "a", "阿司匹林肠溶片", null, listOf("08:00")))
        var undone = 0
        phoneFrame { TodoCard(Todo(plan, CompactFixture.TODAY, "08:00", "taken", DoseLog(json("id" to "d1", "actor_name" to "管理员"))),
            false, record = {}, skip = {}, undo = { undone++ }, speak = {}) }
        compose.onNodeWithText("记录人：管理员，请勿重复服药。").assertIsDisplayed()
        compose.onNodeWithText("核对并记录服药").assertDoesNotExist()
        compose.onNodeWithText("撤销").performClick()
        compose.runOnIdle { assertEquals(1, undone) }
    }

    @Test fun boxCardKeepsAdminEditVisibleAndMovesStockActionsIntoMenu() {
        val item = StockItem(CompactFixture.box("b1", "p1", "阿司匹林肠溶片", "2027-05-31"))
        val moves = mutableListOf<String>()
        var edits = 0
        phoneFrame { BoxCard(item, CompactFixture.TODAY, admin = true, busy = false, edit = { edits++ }) { moves += it } }
        compose.onNodeWithText("修改药盒资料").assertIsDisplayed().performClick()
        compose.onNodeWithText("在库").assertIsDisplayed()
        listOf("用掉", "移到另一药箱", "报废").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("用掉").assertExists()
        compose.onNodeWithText("移到另一药箱").assertExists()
        compose.onNodeWithText("报废").performClick()
        compose.runOnIdle { assertEquals(listOf("discard"), moves); assertEquals(1, edits) }
    }

    @Test fun planCardShowsEditAndAsNeededActionsAndPausesFromMenu() {
        val vm = model()
        val plan = parsePlan(CompactFixture.plan("p3", "a", "布洛芬缓释胶囊", null, emptyList(), frequency = "as_needed"))
        var paused = 0
        var recorded = 0
        val state = vm.state.value
        phoneFrame { PlanCard(plan, state, showOwner = false, edit = {}, pause = { paused++ }, record = { recorded++ }) }
        val edit = compose.onNodeWithText("编辑计划").assertIsDisplayed()
        val record = compose.onNodeWithText("记录一次按需服药").assertIsDisplayed()
        assertTrue(sameRow(bounds(edit), bounds(record)))
        compose.onNodeWithText("暂停计划").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("暂停计划").performClick()
        compose.runOnIdle { assertEquals(1, paused); assertEquals(0, recorded) }
    }

    @Test fun settingsMenuFitsAndEverySubPageOpensAndReturns() {
        val vm = model()
        phoneFrame {
            val nav = rememberNavController()
            val state by vm.state.collectAsState()
            NavHost(nav, "settings") { settingsGraph(nav, vm, state) }
        }
        val frame = bounds(compose.onNodeWithTag("frame"))
        val last = compose.onNodeWithText("使用日志").assertIsDisplayed()
        assertTrue("menu must fit without scrolling", bounds(last).bottom <= frame.bottom)
        for (entry in settingsEntries) {
            compose.onNodeWithText(entry.title).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("返回").assertIsDisplayed()
            compose.onNodeWithText(entry.title).assertIsDisplayed()
            compose.onNodeWithText("使用日志").let { if (entry.id != "logs") it.assertDoesNotExist() }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("使用日志").assertIsDisplayed()
        }
    }

    @Test fun permissionsSubPageShowsEveryPermissionRowWithoutScrolling() {
        val vm = model()
        phoneFrame {
            val nav = rememberNavController()
            val state by vm.state.collectAsState()
            NavHost(nav, "settings") { settingsGraph(nav, vm, state) }
        }
        compose.onNodeWithText("权限与后台").performClick()
        val frame = bounds(compose.onNodeWithTag("frame"))
        listOf("日历权限", "通知权限", "精确闹钟", "电池优化").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertTrue(bounds(compose.onNodeWithText("电池优化")).bottom <= frame.bottom)
        val row = bounds(compose.onNodeWithText("日历权限"))
        val open = compose.onAllNodesWithText("去开启").onFirst().assertIsDisplayed()
        assertTrue("action sits on the same row as its permission", sameRow(row, bounds(open)) || bounds(open).top - row.bottom < 24f)
        compose.onNodeWithContentDescription("通知权限 更多操作").performClick()
        compose.onNodeWithText("系统通知设置").assertExists()
    }

    @Test fun accountSubPageMovesCodeEntryIntoDialog() {
        val vm = model()
        phoneFrame {
            val nav = rememberNavController()
            val state by vm.state.collectAsState()
            NavHost(nav, "settings") { settingsGraph(nav, vm, state) }
        }
        compose.onNodeWithText("账号与安全").performClick()
        compose.onNodeWithText("验证器 6 位验证码").assertDoesNotExist()
        compose.onNodeWithText("退出登录并清除本机日历和闹钟").assertIsDisplayed()
        compose.onNodeWithText("重新生成恢复码").performClick()
        compose.onNodeWithText("验证器 6 位验证码").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("验证器 6 位验证码").assertDoesNotExist()
    }
}
