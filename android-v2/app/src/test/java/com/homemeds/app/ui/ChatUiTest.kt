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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.homemeds.app.Prefs
import com.homemeds.app.User
import com.homemeds.app.appTitle
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
        compose.onNodeWithText("手机直接连接模型（设备加密凭据）").assertDoesNotExist()
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
        compose.onNodeWithText("手机直接连接模型（设备加密凭据）").performClick()
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
