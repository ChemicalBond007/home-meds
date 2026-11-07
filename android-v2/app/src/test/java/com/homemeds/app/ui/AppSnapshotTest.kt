package com.homemeds.app.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.homemeds.app.*
import com.homemeds.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppSnapshotTest {
    private val server = MockWebServer()
    private val store = ViewModelStore()
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        prefs.clear(); prefs.appNotifications = false; prefs.sessionCookie = "session"
        ApiClient.init(context, NativeApi(server.url("/").toString().trimEnd('/'),
            { prefs.sessionCookie }, { prefs.sessionCookie = it }, { prefs.activeGroupId }))
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain(); server.close() }
    private fun snapshot(remaining: Int = 10) = json("api_version" to 1, "binding_only" to false,
        "user" to json("id" to "u", "username" to "成员", "role" to "user", "group_id" to "g", "is_elderly" to 0),
        "groups" to JSONArray().put(json("id" to "g", "name" to "家庭", "timezone" to "Asia/Shanghai", "meal_times" to JSONObject())),
        "group_id" to "g", "members" to JSONArray(), "products" to JSONArray(),
        "stock" to JSONArray().put(json("id" to "b", "product_id" to "p", "name" to "药", "base_unit" to "片",
            "units_per_package" to 10, "units_remaining" to remaining, "status" to "active")),
        "plans" to JSONArray(), "warehouses" to JSONArray(),
        "today" to json("date" to "2026-11-07", "timezone" to "Asia/Shanghai", "items" to JSONArray()))
    private fun enqueue(body: JSONObject) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())) }
    private fun model() = AppViewModel(RuntimeEnvironment.getApplication()).also { store.put("app", it) }

    @Test fun realColdStartAndSaveConsumeOnlyTheirOwnRequestsAndReusePlansForReminders() = runBlocking {
        enqueue(snapshot())
        val vm = model()
        withTimeout(5000) { vm.state.first { !it.loading } }
        assertEquals("g", vm.state.value.groupId)
        assertEquals(1, server.requestCount)
        assertEquals("/api/app/bootstrap", server.takeRequest().path)
        assertEquals("[]", vm.prefs.cachedPlans)
        enqueue(json("id" to "saved", "app_state" to snapshot(9)))
        var saved = false
        vm.save("POST", "/doses", JSONObject()) { saved = true }
        withTimeout(5000) { vm.state.first { !it.busy && it.stock.singleOrNull()?.remaining == 9.0 } }
        assertTrue(saved)
        assertEquals(2, server.requestCount)
        assertEquals("app", server.takeRequest().getHeader("X-HomeMeds-State"))
        vm.syncDebounced()
        dispatcher.scheduler.advanceTimeBy(1600)
        assertEquals(2, server.requestCount)
        enqueue(snapshot(8))
        vm.refresh()
        withTimeout(5000) { vm.state.first { it.stock.singleOrNull()?.remaining == 8.0 } }
        assertEquals(3, server.requestCount)
    }

    @Test fun committedWriteWithNoSnapshotRefreshesOnceWithoutRepeatingWrite() = runBlocking {
        enqueue(snapshot())
        val vm = model()
        withTimeout(5000) { vm.state.first { !it.loading } }
        server.takeRequest()
        enqueue(json("id" to "saved", "state_error" to "已保存，请刷新"))
        enqueue(snapshot(7))
        vm.save("POST", "/stock", JSONObject())
        withTimeout(5000) { vm.state.first { !it.busy && it.stock.singleOrNull()?.remaining == 7.0 } }
        assertEquals(3, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("/api/app/bootstrap", server.takeRequest().path)
    }

    @Test fun snapshotParserRejectsForeignFamilyAndPreservesLocalAssistantPreferences() {
        val previous = AppState(assistantTarget = "target", assistantPhone = false)
        val next = appSnapshot(snapshot(), previous)
        assertEquals("target", next.assistantTarget)
        assertFalse(next.assistantPhone)
        try { appSnapshot(snapshot().put("group_id", "other"), previous); fail("foreign family must fail") }
        catch (_: IllegalArgumentException) { }
    }
}
