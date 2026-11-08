package com.homemeds.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.homemeds.app.MainActivity
import com.homemeds.app.Prefs
import com.homemeds.app.json
import com.homemeds.app.net.ApiClient
import com.homemeds.app.net.NativeApi
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatRotationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val server = MockWebServer()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val chats = AtomicInteger()
    private var slow = false
    private var markdown = false
    private var landscape = false

    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/app/ai/session") return MockResponse().setBody(json(
                    "session_id" to "chat-session", "system_prompt" to "test", "settings" to json(
                        "model" to "test", "max_tokens" to 2048, "timeout_seconds" to 20,
                        "extra_body" to JSONObject(), "search_mode" to "external", "max_llm_calls" to 6,
                        "max_tool_calls" to 5, "output_mode" to "prompt", "query_mode" to "prefetch",
                        "duckduckgo_enabled" to false)).toString())
                val provider = request.path == "/api/app/ai/provider"
                if (provider || request.path.orEmpty().startsWith("/api/ai/chat")) {
                    chats.incrementAndGet()
                    fun delta(text: String) = if (provider)
                        "data: ${json("choices" to JSONArray().put(json("delta" to json("content" to text))))}\n\n"
                    else "event: delta\ndata: ${JSONObject.quote(text)}\n\n"
                    val first = delta(if (markdown) "* **药品**：演示药品" else "已有回复")
                    val rest = delta(if (markdown) "\n* **备注**：核对实物" else "继续完成") + if (provider) "data: [DONE]\n\n"
                        else if (markdown) "event: done\ndata: {}\n\n"
                        else "event: suggestions\ndata: [\"继续核对\"]\n\nevent: done\ndata: {}\n\n"
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(first + rest).apply {
                        if (slow) throttleBody(first.toByteArray().size.toLong(), 4, TimeUnit.SECONDS)
                    }
                }
                return CompactFixture.dispatcher().dispatch(request)
            }
        }
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        prefs.clear(); prefs.appNotifications = false; prefs.sessionCookie = "session"; prefs.assistantPhone = false
        ApiClient.init(context, NativeApi(server.url("/").toString().trimEnd('/'),
            { prefs.sessionCookie }, { prefs.sessionCookie = it }, { prefs.activeGroupId }))
    }

    @After fun cleanup() { scenario?.close(); server.close() }

    private fun launch(phone: Boolean = false) {
        Prefs(RuntimeEnvironment.getApplication()).assistantPhone = phone
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5000) { compose.onAllNodesWithText("助手").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("助手").performClick()
    }

    private fun rotate() {
        landscape = !landscape
        RuntimeEnvironment.setQualifiers(if (landscape) "w640dp-h360dp-land" else "w360dp-h640dp-port")
        scenario!!.recreate()
        compose.waitForIdle()
    }

    private fun assertHistory(text: String) {
        compose.onNodeWithTag("chat_history").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertExists()
    }

    @Test fun completedHistoryAndUnsentInputSurviveActivityRecreation() {
        launch()
        compose.onNodeWithTag("chat_input").performTextInput("旋转前的问题")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("已有回复继续完成").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_input").performTextInput("尚未发送的补充")
        scenario!!.onActivity { activity ->
            val chat = ViewModelProvider(activity)[AppViewModel::class.java].chat
            chat.photos = listOf(PhotoAttachment("draft-photo", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)))
            chat.medicineDrafts = listOf(json("draft_id" to "med", "name" to "旋转前药品草稿"))
            chat.proposals = listOf(parseProposal(json("operation" to "create", "draft" to
                CompactFixture.plan("draft", "a", "旋转前计划草稿", null, listOf("08:00"))), "a"))
        }
        rotate()
        compose.onNodeWithTag("chat_input").assertTextContains("尚未发送的补充")
        compose.onNodeWithText("1 / 6").assertExists()
        compose.onNodeWithText("继续核对").assertExists()
        assertHistory("已有回复继续完成")
        assertHistory("旋转前的问题")
        assertHistory("旋转前药品草稿")
        assertHistory("旋转前计划草稿")
        assertEquals(1, chats.get())
    }

    @Test fun assistantFormatsMarkdownWhileStreamingAndAfterCompletionButUserTextStaysLiteral() {
        markdown = true; slow = true
        launch()
        compose.onNodeWithTag("chat_input").performTextInput("用户的 **原文**")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("药品：演示药品").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("* **药品**：演示药品").assertDoesNotExist()
        compose.onNodeWithContentDescription("停止回复").assertExists()
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("发送").fetchSemanticsNodes().isNotEmpty() }
        assertHistory("药品：演示药品")
        assertHistory("备注：核对实物")
        assertHistory("用户的 **原文**")
        assertEquals(1, chats.get())
    }

    @Test fun activeServerReplySurvivesRecreationWithoutRestartingRequest() = verifyActiveReply(false)
    @Test fun activePhoneAgentReplySurvivesRecreationWithoutRestartingRequest() = verifyActiveReply(true)

    private fun verifyActiveReply(phone: Boolean) {
        slow = true
        launch(phone)
        compose.onNodeWithTag("chat_input").performTextInput("请继续回复")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("已有回复").fetchSemanticsNodes().isNotEmpty() }
        rotate()
        assertHistory("已有回复")
        compose.onNodeWithContentDescription("停止回复").assertExists()
        compose.waitUntil(15000) { compose.onAllNodesWithText("已有回复继续完成").fetchSemanticsNodes().isNotEmpty() }
        rotate()
        assertHistory("已有回复继续完成")
        assertEquals(1, chats.get())
    }

    @Test fun clearingAfterRotationCancelsReplyAndDoesNotRestoreItOnNextRotation() {
        slow = true
        launch()
        compose.onNodeWithTag("chat_input").performTextInput("待清空的问题")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("已有回复").fetchSemanticsNodes().isNotEmpty() }
        rotate()
        compose.onNodeWithContentDescription("清空对话历史").performClick()
        compose.onNodeWithText("确认清空").performClick()
        rotate()
        compose.onNodeWithText("待清空的问题").assertDoesNotExist()
        compose.onNodeWithText("已有回复").assertDoesNotExist()
        compose.onNodeWithContentDescription("停止回复").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        assertEquals(1, chats.get())
    }
}
