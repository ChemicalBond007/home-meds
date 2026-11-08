package com.homemeds.app

import android.app.Application
import com.homemeds.app.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProviderProxyTest {
    private val settings get() = json("model" to "test-model", "max_tokens" to 2048, "timeout_seconds" to 10,
        "extra_body" to JSONObject(), "search_mode" to "external", "max_llm_calls" to 6, "max_tool_calls" to 5,
        "output_mode" to "prompt", "query_mode" to "prefetch", "duckduckgo_enabled" to false)

    private fun init(server: MockWebServer) {
        ApiClient.init(RuntimeEnvironment.getApplication(), NativeApi(server.url("/").toString().trimEnd('/'),
            { "cookie" }, {}, { "g" }))
    }
    private fun session(id: String) = MockResponse().setBody(json("session_id" to id, "settings" to settings,
        "expires_at" to Long.MAX_VALUE, "system_prompt" to "test").toString())

    @Test fun chatUsesCredentialFreeSessionAndAuthenticatedWorkerStream() = runBlocking {
        MockWebServer().use { server ->
            init(server)
            server.enqueue(session("s"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            val events = mutableListOf<Pair<String, Any>>()
            AiAgent.runChat(json("user_id" to "u", "intent" to "chat", "messages" to JSONArray().put(AiAgent.userMessage("hi"))),
                "g") { e, d -> events += e to d }
            assertTrue(events.any { it.first == "delta" && it.second == "hello" })
            val open = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/app/ai/session", open.path)
            assertFalse(JSONObject(open.body.readUtf8()).has("public_key"))
            val provider = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/app/ai/provider", provider.path)
            assertEquals("hm_session=cookie", provider.getHeader("Cookie"))
            assertEquals("g", provider.getHeader("X-Group-Id"))
            assertNull(provider.getHeader("Authorization")); assertNull(provider.getHeader("x-api-key"))
            val request = JSONObject(provider.body.readUtf8())
            assertEquals("s", request.getString("session_id")); assertEquals("llm", request.getString("service"))
            assertEquals("/chat/completions", request.getString("path"))
            assertEquals("/api/app/ai/finish", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test fun lookupProxiesTavilyAndJsonModelRequests() = runBlocking {
        MockWebServer().use { server ->
            init(server); server.enqueue(session("lookup"))
            server.enqueue(MockResponse().setBody("""{"results":[{"title":"source","url":"https://example.com/medicine","content":"test"}]}"""))
            server.enqueue(MockResponse().setBody(json("choices" to JSONArray().put(json("message" to json(
                "role" to "assistant", "content" to json("name" to "test", "sources" to JSONArray()).toString())))).toString()))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            AiAgent.runLookup("test", "g") { _, _ -> }
            server.takeRequest()
            val search = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("tavily", search.getString("service")); assertEquals("/search", search.getString("path"))
            val model = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("llm", model.getString("service")); assertEquals("/chat/completions", model.getString("path"))
            assertEquals("/api/app/ai/finish", server.takeRequest().path)
        }
    }

    @Test fun legacy501FallsBackToWorkerChatWithoutProviderCredentials() = runBlocking {
        MockWebServer().use { server ->
            init(server); server.enqueue(MockResponse().setResponseCode(501).setBody("{}"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: delta\ndata: \"fallback\"\n\nevent: done\ndata: {}\n\n"))
            AiAgent.runChat(json("user_id" to "u", "intent" to "chat",
                "messages" to JSONArray().put(AiAgent.userMessage("hi"))), "g") { _, _ -> }
            assertEquals("/api/app/ai/session", server.takeRequest().path)
            assertEquals("/api/ai/chat", server.takeRequest().path)
        }
    }

    @Test fun builtinSearchProtocolsAllUseWorkerProxyBeforeJsonFormatting() = runBlocking {
        val url = "https://example.com/medicine"
        for ((protocol, path, response) in listOf(
            Triple("responses", "/responses",
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"public","annotations":[{"type":"url_citation","url":"$url","title":"source"}]}]}]}"""),
            Triple("anthropic", "/messages",
                """{"content":[{"type":"text","text":"public","citations":[{"type":"web_search_result_location","url":"$url","title":"source"}]}]}"""),
            Triple("chat_completions", "/chat/completions",
                """{"choices":[{"message":{"content":"public"}}],"citations":["$url"]}"""),
        )) {
            MockWebServer().use { server ->
                init(server)
                val config = settings.put("search_mode", "builtin").put("native_search_api", protocol)
                    .put("native_search_body", JSONObject())
                server.enqueue(MockResponse().setBody(json("session_id" to "lookup", "settings" to config).toString()))
                server.enqueue(MockResponse().setBody(response))
                server.enqueue(MockResponse().setBody(json("choices" to JSONArray().put(json("message" to json(
                    "role" to "assistant", "content" to json("name" to "test", "sources" to JSONArray()).toString())))).toString()))
                server.enqueue(MockResponse().setBody("{\"ok\":true}"))
                var result: JSONObject? = null
                AiAgent.runLookup("test", "g") { event, data -> if (event == "result") result = data as JSONObject }
                assertEquals(url, result!!.getJSONArray("sources").getJSONObject(0).getString("url"))
                assertEquals("/api/app/ai/session", server.takeRequest().path)
                val builtin = server.takeRequest()
                assertEquals("/api/app/ai/provider", builtin.path)
                assertEquals(path, JSONObject(builtin.body.readUtf8()).getString("path"))
                assertNull(builtin.getHeader("Authorization")); assertNull(builtin.getHeader("x-api-key"))
                assertEquals("/api/app/ai/provider", server.takeRequest().path)
                assertEquals("/api/app/ai/finish", server.takeRequest().path)
            }
        }
    }
}
