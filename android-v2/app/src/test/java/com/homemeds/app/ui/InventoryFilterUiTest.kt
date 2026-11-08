package com.homemeds.app.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.homemeds.app.*
import com.homemeds.app.net.ApiClient
import com.homemeds.app.net.NativeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InventoryFilterUiTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val store = ViewModelStore()
    private lateinit var prefs: Prefs

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = CompactFixture.dispatcher()
        server.start()
        val context = RuntimeEnvironment.getApplication()
        prefs = Prefs(context)
        prefs.clear(); prefs.appNotifications = false; prefs.sessionCookie = "session"
        ApiClient.init(context, NativeApi(server.url("/").toString().trimEnd('/'),
            { prefs.sessionCookie }, { prefs.sessionCookie = it }, { prefs.activeGroupId }))
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain(); server.close() }

    private fun show(): AppViewModel {
        val vm = AppViewModel(RuntimeEnvironment.getApplication()).also { store.put("app", it) }
        runBlocking { withTimeout(5000) { vm.state.first { !it.loading } } }
        compose.setContent { HomeMedsTheme(false) {
            val state by vm.state.collectAsState()
            Box(Modifier.width(360.dp).height(482.dp)) { InventoryScreen(vm, state) }
        } }
        return vm
    }
    /** Box expiry dates in on-screen order, read from each card's detail line. */
    private fun expiries() = compose.onAllNodes(hasText("有效期至", substring = true)).fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString()?.substringAfter("有效期至 ")?.take(10) }

    @Test fun sortsByExpiryAndFiltersByWarehouseAndExpiryAndRemembersChoice() {
        show()
        compose.onNodeWithText("显示 3 / 3 盒 · 在库 · 按有效期从早到晚排序").assertExists()
        assertEquals(listOf("2026-10-01", "2026-11-20", "2027-05-31"), expiries())

        compose.onNodeWithText("有效期从早到晚").performClick()
        compose.onNodeWithText("有效期从晚到早").performClick()
        compose.onNodeWithText("显示 3 / 3 盒 · 在库 · 按有效期从晚到早排序").assertExists()
        assertEquals(listOf("2027-05-31", "2026-11-20", "2026-10-01"), expiries())

        compose.onNodeWithText("全部药箱").performClick()
        compose.onNodeWithText("卧室药箱").performClick()
        compose.onNodeWithText("当前条件下没有药盒").assertExists()

        compose.onNodeWithText("恢复默认").performClick()
        compose.onNodeWithText("更多筛选").performClick()
        compose.onNodeWithText("筛选与排序药盒").assertExists()
        compose.onNodeWithText("全部有效期").performClick()
        // The expired box card also shows an "已过期" chip, so pick the clickable menu item.
        compose.onNode(hasText("已过期") and hasClickAction()).performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("更多筛选 · 1").assertExists()
        compose.onNodeWithText("显示 1 / 3 盒 · 在库 · 已过期 · 按有效期从早到晚排序").assertExists()
        compose.onAllNodesWithText("阿司匹林肠溶片").assertCountEquals(1)
        compose.onNodeWithText("二甲双胍缓释片").assertDoesNotExist()

        assertEquals("expired", BoxFilter.parse(prefs.boxFilter("g")).expiry)
    }

    @Test fun savedFilterIsRestoredAndWarehouseSortShowsGroupHeaders() {
        prefs.setBoxFilter("g", BoxFilter(sort = "warehouse").toJson().toString())
        show()
        compose.onNodeWithText("客厅药箱 · 3 盒").assertExists()
        compose.onNodeWithText("按药箱分组").assertExists()
    }
}
