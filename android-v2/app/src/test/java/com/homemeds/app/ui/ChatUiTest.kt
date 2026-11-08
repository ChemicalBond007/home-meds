package com.homemeds.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.homemeds.app.Prefs
import com.homemeds.app.User
import com.homemeds.app.appTitle
import com.homemeds.app.AppNavigationBar
import com.homemeds.app.AssistantHeader
import com.homemeds.app.appendRecognizedText
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatUiTest {
    @get:Rule val compose = createComposeRule()
    private val self = User("u1", "本人", "member", "g1", false)
    private val family = User("u2", "家人", "member", "g1", false)
    private var text by mutableStateOf("")
    private var running by mutableStateOf(false)
    private var suggestions by mutableStateOf(emptyList<String>())
    private var photos by mutableStateOf(emptyList<PhotoAttachment>())
    private var compact by mutableStateOf(false)
    private var sends = 0
    private var camera = 0
    private var voice = 0
    private var stops = 0

    @Test fun recognizedSpeechAppendsWithoutReplacingTypedTextAndRespectsLimit() {
        assertEquals("请记录阿司匹林每天一次", appendRecognizedText("请记录", " 阿司匹林每天一次 "))
        assertEquals("保留原文 ", appendRecognizedText("保留原文 ", " \n "))
        assertEquals("再次听写", appendRecognizedText(appendRecognizedText("", "再次"), "听写"))
        assertEquals("甲".repeat(5999) + "乙", appendRecognizedText("甲".repeat(5999), "乙丙"))
        assertEquals(6000, appendRecognizedText("甲".repeat(6001), "").length)
        assertEquals("甲".repeat(6000), appendRecognizedText("甲".repeat(6000), "乙"))
    }

    @Test fun recognizedSpeechAppearsAfterTheCurrentInputWithoutSending() {
        text = "请记录"
        screen()
        compose.onNodeWithTag("chat_input").performTextInputSelection(TextRange(text.length))
        compose.onNodeWithTag("chat_input").performTextInput("已有医嘱")
        compose.runOnIdle { text = appendRecognizedText(text, " 每天一次 ") }
        compose.onNodeWithTag("chat_input").assertTextContains("请记录已有医嘱每天一次")
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun clearButtonIsToTheRightOfAssistantTitleAndDisabledWhileSaving() {
        var enabled by mutableStateOf(false)
        var requests = 0
        compose.setContent { MaterialTheme { AssistantHeader(enabled) { requests++ } } }
        val title = compose.onNodeWithText("赛博药箱AI助手").fetchSemanticsNode().boundsInRoot
        val clear = compose.onNodeWithContentDescription("清空对话历史")
        clear.assertIsNotEnabled()
        assertTrue(clear.fetchSemanticsNode().boundsInRoot.left >= title.right)
        compose.runOnIdle { enabled = true }
        clear.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun clearingRequiresConfirmationResetsSessionAndCancelsOldStreamWithoutRevivingHistory() {
        var requested by mutableStateOf(false)
        var cancelled = 0
        var latestHistory = emptyList<String>()
        compose.setContent { MaterialTheme {
            Column {
                Text("已保存的药品和计划")
                AssistantHeader(true) { requested = true }
                ClearableChat(requested, { requested = false }) {
                    var history by remember { mutableStateOf(emptyList<String>()) }
                    var input by remember { mutableStateOf("") }
                    var attachments by remember { mutableStateOf(emptyList<PhotoAttachment>()) }
                    var replies by remember { mutableStateOf(emptyList<String>()) }
                    var draft by remember { mutableStateOf(false) }
                    SideEffect { latestHistory = history }
                    val scope = rememberCoroutineScope()
                    Column {
                        Action("准备测试对话") {
                            history = listOf("旧用户消息", "旧助手回复")
                            input = "未发送的文字"
                            attachments = listOf(PhotoAttachment("old-photo", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)))
                            replies = listOf("旧推荐回复")
                            draft = true
                            scope.launch {
                                try { awaitCancellation() }
                                finally { history = history + "旧流结束后的消息"; cancelled++ }
                            }
                        }
                        ChatLayout(history.size, composer = {
                            ChatComposer(input, { input = it }, attachments, replies, false,
                                canSend = input.isNotBlank() || attachments.isNotEmpty(), photograph = {},
                                removePhoto = {}, voice = {}, send = {}, stop = {})
                        }) {
                            items(history) { Text(it) }
                            if (draft) item { Text("未保存草稿") }
                        }
                    }
                }
            }
        } }
        compose.onNodeWithText("准备测试对话").performClick()
        compose.onNodeWithContentDescription("清空对话历史").performClick()
        compose.onNodeWithText("确认清空").assertExists()
        compose.runOnIdle { assertEquals(0, cancelled) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("chat_history").performScrollToIndex(0)
        compose.onNodeWithText("旧用户消息").assertExists()
        compose.onNodeWithTag("chat_input").assertTextContains("未发送的文字")
        compose.onNodeWithText("旧推荐回复").assertExists()
        compose.onNodeWithTag("chat_history").performScrollToIndex(2)
        compose.onNodeWithText("未保存草稿").assertExists()
        compose.runOnIdle { assertEquals(0, cancelled) }
        repeat(2) { index ->
            if (index > 0) compose.onNodeWithText("准备测试对话").performClick()
            compose.onNodeWithContentDescription("清空对话历史").performClick()
            compose.onNodeWithText("确认清空").performClick()
            compose.onNodeWithText("旧用户消息").assertDoesNotExist()
            compose.onNodeWithText("旧助手回复").assertDoesNotExist()
            compose.onNodeWithText("旧流结束后的消息").assertDoesNotExist()
            compose.onNodeWithText("旧推荐回复").assertDoesNotExist()
            compose.onNodeWithText("未保存草稿").assertDoesNotExist()
            compose.onNodeWithContentDescription("移除照片 1").assertDoesNotExist()
            compose.onNodeWithTag("chat_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
            compose.onNodeWithText("已保存的药品和计划").assertExists()
            compose.runOnIdle { assertEquals(index + 1, cancelled); assertFalse(requested); assertTrue(latestHistory.isEmpty()) }
        }
    }

    @Test fun assistantIsTheMiddleTabAndPlanTabStillNavigatesToPlans() {
        var route by mutableStateOf("chat")
        compose.setContent { MaterialTheme { AppNavigationBar(route, false) { route = it } } }
        val labels = listOf("今日", "药箱", "助手", "计划", "设置")
        val centers = labels.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.center.x }
        assertEquals(centers.sorted(), centers)
        assertEquals((centers.first() + centers.last()) / 2, centers[2], 1f)
        compose.onNodeWithText("助手").assertIsSelected()
        compose.onNodeWithText("计划").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("plans", route) }
        compose.onNodeWithText("助手").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("chat", route) }
    }

    @Test fun reorderedTabsKeepElderlyNavigationRestrictions() {
        compose.setContent { MaterialTheme { AppNavigationBar("home", true) {} } }
        compose.onNodeWithText("今日").assertExists()
        compose.onNodeWithText("药箱").assertExists()
        compose.onNodeWithText("设置").assertExists()
        compose.onNodeWithText("计划").assertDoesNotExist()
        compose.onNodeWithText("助手").assertDoesNotExist()
    }

    private fun screen() {
        compose.setContent { MaterialTheme {
            Box((if (compact) Modifier.fillMaxWidth().height(220.dp) else Modifier.fillMaxSize()).testTag("chat_frame")) {
            ChatLayout(0, composer = {
                ChatComposer(text, { text = it }, photos, suggestions, running,
                    canSend = text.isNotBlank() || photos.isNotEmpty(),
                    photograph = { camera++ }, removePhoto = { index -> photos = photos.filterIndexed { i, _ -> i != index } },
                    voice = { voice++ }, send = { sends++ }, stop = { stops++ })
            }) { items((0 until 60).toList()) { Text("历史消息 $it") } }
            }
        } }
    }

    @Test fun composerActionsRemainVisibleWhenKeyboardReducesHeightWithSixPhotos() {
        text = "已有医嘱中的记录内容。".repeat(30)
        photos = List(6) { PhotoAttachment("photo-$it", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) }
        suggestions = listOf("我补拍盒底", "我核对已有医嘱", "我补充信息")
        screen()
        compose.runOnIdle { compact = true }
        val frame = compose.onNodeWithTag("chat_frame").fetchSemanticsNode().boundsInRoot
        for (action in listOf("拍照或选择照片", "语音输入", "发送")) {
            compose.onNodeWithContentDescription(action).assertIsDisplayed()
            val bounds = compose.onNodeWithContentDescription(action).fetchSemanticsNode().boundsInRoot
            assertTrue("$action must stay above keyboard", bounds.bottom <= frame.bottom)
        }
    }

    @Test fun inputStaysAtBottomWhenHistoryScrollsAndButtonsAreInsideComposer() {
        screen()
        val input = compose.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
        val history = compose.onNodeWithTag("chat_history").fetchSemanticsNode().boundsInRoot
        assertTrue(input.top >= history.bottom)
        compose.onNodeWithTag("chat_history").performScrollToIndex(50)
        assertEquals(input, compose.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot)
        val camera = compose.onNodeWithContentDescription("拍照或选择照片").fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithContentDescription("语音输入").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
        assertTrue(input.contains(camera.center) && input.contains(mic.center) && input.contains(send.center))
        assertTrue(mic.left > input.center.x && mic.right <= send.left)
        assertEquals(mic.bottom, send.bottom, 1f)
        compose.onNodeWithText("中文语音输入").assertDoesNotExist()
        compose.onNodeWithText("生成待确认草稿").assertDoesNotExist()
        compose.onNodeWithText("计划对象").assertDoesNotExist()
        compose.onNodeWithText("手机运行 AI 循环（Worker 代理）").assertDoesNotExist()
    }

    @Test fun suggestionsComeFromModelAndOnlyFillInputUntilExplicitSend() {
        screen()
        compose.onNodeWithTag("reply_suggestions").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        compose.runOnIdle { suggestions = listOf("我补拍盒底") }
        compose.onNodeWithText("我补拍盒底").performClick()
        compose.onNodeWithTag("chat_input").assertTextContains("我补拍盒底")
        val suggestion = compose.onNodeWithTag("reply_suggestions").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot
        assertTrue(suggestion.bottom <= input.top)
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun cameraVoiceAndKeyboardSendUseTheirOwnActionsAndStopReplacesSend() {
        screen()
        compose.onNodeWithContentDescription("拍照或选择照片").performClick()
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.onNodeWithTag("chat_input").performTextInput("请记录已有医嘱")
        compose.onNodeWithTag("chat_input").performImeAction()
        compose.runOnIdle { assertEquals(1, camera); assertEquals(1, voice); assertEquals(1, sends); running = true }
        compose.onNodeWithContentDescription("发送").assertDoesNotExist()
        compose.onNodeWithContentDescription("拍照或选择照片").assertIsNotEnabled()
        compose.onNodeWithContentDescription("语音输入").assertIsNotEnabled()
        compose.onNodeWithContentDescription("停止回复").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun sixAttachmentsCanBeRemovedAndPhotoOnlyMessageCanBeSent() {
        photos = List(6) { PhotoAttachment("photo-$it", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) }
        screen()
        compose.onNodeWithText("6 / 6").assertExists()
        compose.onNodeWithContentDescription("发送").assertIsEnabled()
        compose.onNodeWithContentDescription("移除照片 1").performClick()
        compose.onNodeWithText("5 / 6").assertExists()
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { assertEquals("photo-1", photos.first().dataUrl); assertEquals(1, sends) }
    }

    @Test fun settingsExposeAssistantPreferencesToNonAdminAndKeepSelectedValues() {
        var state by mutableStateOf(AppState(user = self, groupId = "g1", members = listOf(self, family)))
        compose.setContent { MaterialTheme {
            Column { AssistantPreferencesPanel(state, { state = state.copy(assistantTarget = it) },
                { state = state.copy(assistantPhone = it) }) }
        } }
        compose.onNodeWithText("计划对象").assertExists()
        compose.onNodeWithText("本人").performClick()
        compose.onNodeWithText("家人").performClick()
        compose.onNodeWithText("手机运行 AI 循环（Worker 代理）").performClick()
        compose.runOnIdle { assertEquals("u2", assistantTarget(state)); assertFalse(state.assistantPhone) }
    }

    @Test fun preferenceSurvivesRecreationAndTargetCannotCrossAccountsOrFamilies() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        prefs.clear()
        assertTrue(prefs.assistantPhone)
        assertNull(prefs.assistantTarget("u1", "g1"))
        prefs.assistantPhone = false
        prefs.setAssistantTarget("u1", "g1", "u2")
        val restored = Prefs(context)
        assertFalse(restored.assistantPhone)
        assertEquals("u2", restored.assistantTarget("u1", "g1"))
        assertNull(restored.assistantTarget("u1", "g2"))
        assertNull(restored.assistantTarget("u2", "g1"))
        val state = AppState(user = self, members = listOf(self, family))
        assertEquals("u1", assistantTarget(state))
        assertEquals("u2", assistantTarget(state.copy(assistantTarget = "u2")))
        assertNull(assistantTarget(state.copy(assistantTarget = "removed")))
        assertNull(assistantTarget(state.copy(user = self.copy(id = "admin", role = "admin"))))
        restored.clear()
        assertTrue(restored.assistantPhone)
        assertNull(restored.assistantTarget("u1", "g1"))
    }

    @Test fun assistantHasItsOwnExactTopBarTitleWithoutRenamingOtherPages() {
        assertEquals("赛博药箱AI助手", appTitle("chat"))
        assertEquals("赛博药箱", appTitle("home"))
        assertEquals("赛博药箱", appTitle("settings"))
    }
}
