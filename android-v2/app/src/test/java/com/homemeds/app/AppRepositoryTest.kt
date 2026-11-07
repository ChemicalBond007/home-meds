package com.homemeds.app

import com.homemeds.app.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppRepositoryTest {
    private fun response(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun api(server: MockWebServer) = NativeApi(server.url("/").toString().trimEnd('/'), { "cookie" }, {}, { "g" })

    @Test fun bootstrapMutationAndReminderSyncEachUseOneRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = AppRepository(api(server))
            server.enqueue(response(json("api_version" to 1)))
            repo.bootstrap("g")
            assertEquals(1, server.requestCount)
            assertEquals("/api/app/bootstrap", server.takeRequest().path)
            server.enqueue(response(json("id" to "new", "app_state" to json("api_version" to 1))))
            assertEquals("new", repo.mutate("POST", "/plans", JSONObject(), "g").getString("id"))
            val write = server.takeRequest()
            assertEquals("app", write.getHeader("X-HomeMeds-State"))
            assertEquals("android", write.getHeader("X-HomeMeds-Client"))
            assertEquals(2, server.requestCount)
            server.enqueue(response(json("group_id" to "g", "timezone" to "Asia/Shanghai", "plans" to JSONArray())))
            repo.sync("g")
            assertEquals(3, server.requestCount)
            assertEquals("/api/app/sync", server.takeRequest().path)
        }
    }

    @Test fun missingBootstrapFallsBackOnceThenKeepsLegacyWritesUnchanged() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = AppRepository(api(server))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"接口不存在"}"""))
            server.enqueue(response(json("user" to json("id" to "u", "username" to "成员", "role" to "user", "group_id" to null, "is_elderly" to 0), "binding_only" to false)))
            server.enqueue(response(JSONArray()))
            assertEquals(0, repo.bootstrap(null).getInt("api_version"))
            assertEquals(3, server.requestCount)
            repeat(3) { server.takeRequest() }
            server.enqueue(response(json("ok" to true)))
            repo.mutate("POST", "/stock", JSONObject(), "g")
            assertNull(server.takeRequest().getHeader("X-HomeMeds-State"))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun authorizationAndNetworkErrorsDoNotTriggerRedundantLegacyReadsOrRetryWrites() = runBlocking {
        for (status in listOf(401, 403, 500)) MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"失败"}"""))
            try { AppRepository(api(server)).bootstrap("g"); fail("expected error") } catch (e: ApiException) { assertEquals(status, e.status) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun globalSettingsDoNotRequestAnUnrelatedFamilySnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(json("ok" to true, "settings" to JSONObject())))
            AppRepository(api(server)).mutate("PUT", "/ai/settings", JSONObject(), "g")
            assertNull(server.takeRequest().getHeader("X-HomeMeds-State"))
            assertEquals(1, server.requestCount)
        }
    }
}
