package com.homemeds.app

import com.homemeds.app.net.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.util.concurrent.TimeUnit

class ApiClientTest {
    @Test fun cookieOriginGroupAndArrayContracts() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var cookie: String? = null
            val origin = server.url("/").toString().trimEnd('/')
            val api = NativeApi(origin, { cookie }, { cookie = it }, { "g1" })
            server.enqueue(MockResponse().setHeader("Set-Cookie", "hm_session=test-token; Path=/; Max-Age=3600; HttpOnly").setBody("""{"ok":true}"""))
            api.obj("POST", "/auth/login", json("username" to "test"))
            assertEquals("test-token", cookie)
            val login = server.takeRequest()
            assertEquals(origin, login.getHeader("Origin"))
            assertEquals("/api/auth/login", login.path)
            server.enqueue(MockResponse().setBody("""[{"id":"p1"}]"""))
            assertEquals("p1", api.array("/plans").getJSONObject(0).getString("id"))
            val plans = server.takeRequest()
            assertEquals("hm_session=test-token", plans.getHeader("Cookie"))
            assertEquals("g1", plans.getHeader("X-Group-Id"))
            assertNull(plans.getHeader("Origin"))
            server.enqueue(MockResponse().setHeader("Set-Cookie", "hm_session=; Max-Age=0; Path=/").setBody("""{"ok":true}"""))
            api.obj("POST", "/auth/logout")
            assertNull(cookie)
        }
    }
    @Test fun errorsAndMalformedJsonAreNotSilentlyAccepted() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var cookie: String? = "old"
            val api = NativeApi(server.url("/").toString().trimEnd('/'), { cookie }, { cookie = it }, { null })
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"请先登录"}"""))
            try { api.obj("GET", "/auth/me"); fail("expected 401") }
            catch (e: ApiException) { assertEquals(401, e.status); assertEquals("请先登录", e.message) }
            assertNull(cookie)
            server.enqueue(MockResponse().setBody("<html>not JSON</html>"))
            try { api.obj("GET", "/today"); fail("expected invalid JSON") }
            catch (e: Exception) { assertTrue(e is java.io.IOException || e is org.json.JSONException) }
        }
    }
    @Test fun streamFramingIncludesMultilineAndFinalEvent() = runBlocking {
        val events = mutableListOf<Pair<String, String>>()
        readSse(StringReader(": heartbeat\r\nevent: delta\r\ndata: line1\r\ndata: line2\r\n\r\nevent: done\ndata: {}").buffered()) { e,d -> events += e to d }
        assertEquals(listOf("delta" to "line1\nline2", "done" to "{}"), events)
    }
    @Test fun sseReportsServerErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = NativeApi(server.url("/").toString().trimEnd('/'), { null }, {}, { null })
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: error\ndata: \"查询失败\"\n\n"))
            try { api.sse("/ai/chat", json()) { _,_ -> }; fail("expected error event") }
            catch (e: UserError) { assertEquals("查询失败", e.message) }
        }
    }
    @Test fun cancelsSocketWhenCoroutineIsCancelled() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = NativeApi(server.url("/").toString().trimEnd('/'), { null }, {}, { null })
            server.enqueue(MockResponse().setBody("""{"ok":true}""").setBodyDelay(10, TimeUnit.SECONDS))
            val job = launch(Dispatchers.Default) { api.obj("GET", "/today") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
