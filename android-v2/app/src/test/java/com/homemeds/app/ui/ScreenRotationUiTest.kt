package com.homemeds.app.ui

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.homemeds.app.BuildConfig
import com.homemeds.app.MainActivity
import com.homemeds.app.Prefs
import com.homemeds.app.R
import com.homemeds.app.net.ApiClient
import com.homemeds.app.net.NativeApi
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRotationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val server = MockWebServer()
    private lateinit var controller: ActivityController<MainActivity>
    private var landscape = false
    private val lookups = AtomicInteger()

    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/app/ai/session") return MockResponse().setResponseCode(501)
                if (request.path == "/api/ai/lookup") {
                    lookups.incrementAndGet()
                    val first = "event: progress\ndata: \"旋转时正在检索\"\n\n"
                    return MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody(first + "event: result\ndata: {\"name\":\"旋转后查询结果\"}\n\n")
                        .throttleBody(first.toByteArray().size.toLong(), 3, TimeUnit.SECONDS)
                }
                return CompactFixture.dispatcher().dispatch(request)
            }
        }
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        prefs.clear(); prefs.appNotifications = false; prefs.sessionCookie = "session"
        ApiClient.init(context, NativeApi(server.url("/").toString().trimEnd('/'),
            { prefs.sessionCookie }, { prefs.sessionCookie = it }, { prefs.activeGroupId }))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitUntil(5000) { compose.onAllNodesWithText("助手").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        server.close()
    }

    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    // Let Android's manifest policy decide whether to recreate, rather than forcing recreate().
    private fun rotate() {
        val before = controller.get()
        landscape = !landscape
        val next = Configuration(before.resources.configuration).apply {
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = if (landscape) 640 else 360
            screenHeightDp = if (landscape) 360 else 640
        }
        controller.configurationChange(next)
        compose.waitForIdle()
        assertSame("Rotation must retain the page composition and its jobs", before, controller.get())
        assertEquals(next.orientation, controller.get().resources.configuration.orientation)
    }

    @Test fun inventorySearchAndNestedProductDraftSurviveRepeatedRotation() {
        compose.onNodeWithText("药箱").performClick()
        compose.onNodeWithText("搜索药名、批号、药箱或追溯码").performTextInput("未提交的搜索")
        rotate()
        compose.onNodeWithText("搜索药名、批号、药箱或追溯码").assertTextContains("未提交的搜索")
        click("药品入库")
        click("新建药品资料")
        compose.onNodeWithText("药品名称").performTextInput("旋转前药品草稿")
        rotate()
        compose.onNodeWithText("药品名称").assertTextContains("旋转前药品草稿")
        rotate()
        compose.onNodeWithText("药品名称").assertTextContains("旋转前药品草稿")
    }

    @Test fun intakeSecondStepAndPackageFieldsSurviveRotation() {
        compose.onNodeWithText("药箱").performClick()
        click("药品入库")
        click("请选择")
        compose.onNode(hasText("阿司匹林肠溶片") and hasClickAction()).performClick()
        click("下一步：核对药盒")
        compose.onNodeWithText("批号（可空）").performTextInput("ROTATE-42")
        rotate()
        compose.onNodeWithText("药品入库 · 第 2 步").assertExists()
        compose.onNodeWithText("批号（可空）").assertTextContains("ROTATE-42")
        rotate()
        compose.onNodeWithText("批号（可空）").assertTextContains("ROTATE-42")
    }

    @Test fun doseDialogRetainsChangedAmountAndTime() {
        compose.onAllNodesWithText("核对并记录服药").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("实际用量（片）").performTextReplacement("2")
        compose.onNodeWithText("时间 HH:mm").performTextReplacement("08:13")
        rotate()
        compose.onNodeWithText("实际用量（片）").assertTextContains("2")
        compose.onNodeWithText("时间 HH:mm").assertTextContains("08:13")
        rotate()
        compose.onNodeWithText("时间 HH:mm").assertTextContains("08:13")
    }

    @Test fun loginDraftSurvivesRotation() {
        compose.runOnIdle { ViewModelProvider(controller.get())[AppViewModel::class.java].logout() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("用户名").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("用户名").performTextInput("未提交用户名")
        rotate()
        compose.onNodeWithText("用户名").assertTextContains("未提交用户名")
    }

    @Test fun photoDialogRetainsUnsavedRemovalAcrossRotation() {
        compose.runOnIdle {
            ViewModelProvider(controller.get())[AppViewModel::class.java].chat.photos = List(2) {
                PhotoAttachment("photo-$it", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
            }
        }
        compose.onNodeWithText("助手").performClick()
        compose.onNodeWithContentDescription("拍照或选择照片").performClick()
        compose.onNode(hasContentDescription("移除照片 1") and hasAnyAncestor(hasTestTag("medicine_photos"))).performClick()
        compose.onNodeWithText("已添加 1 / 6 张").assertExists()
        rotate()
        compose.onNodeWithText("已添加 1 / 6 张").assertExists()
        rotate()
        compose.onNodeWithText("已添加 1 / 6 张").assertExists()
        compose.onNodeWithContentDescription("使用这些照片（1 张）").performClick()
        compose.runOnIdle {
            assertEquals("photo-1", ViewModelProvider(controller.get())[AppViewModel::class.java].chat.photos.single().dataUrl)
        }
    }

    @Test fun activePageQueryContinuesAfterRotationWithoutDuplicateRequest() {
        compose.onNodeWithText("药箱").performClick()
        click("药品入库")
        click("AI 检索（可选）")
        compose.onNodeWithText("AI 检索药名 / 厂家 / 条码").performTextInput("测试药品")
        click("AI 检索预填")
        compose.waitUntil(5000) { compose.onAllNodesWithText("旋转时正在检索").fetchSemanticsNodes().isNotEmpty() }
        rotate()
        compose.onNodeWithText("取消查询").assertExists()
        compose.waitUntil(10000) { compose.onAllNodesWithText("核对药品资料").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("药品名称").assertTextContains("旋转后查询结果")
        rotate()
        compose.onNodeWithText("药品名称").assertTextContains("旋转后查询结果")
        assertEquals(1, lookups.get())
    }

    @Test fun planDraftSurvivesRotation() {
        compose.onNodeWithText("计划").performClick()
        click("新建吃药计划")
        compose.onNodeWithText("药名").performTextInput("未保存计划")
        rotate()
        compose.onNodeWithText("核对吃药计划").assertExists()
        compose.onNodeWithText("药名").assertTextContains("未保存计划")
        rotate()
        compose.onNodeWithText("药名").assertTextContains("未保存计划")
    }

    @Test fun accountVerificationDialogSurvivesRotation() {
        compose.onNodeWithText("设置").performClick()
        click("账号与安全")
        click("重新生成恢复码")
        compose.onNodeWithText("验证器 6 位验证码").performTextInput("123")
        rotate()
        compose.onNodeWithText("验证器 6 位验证码").assertTextContains("123")
        rotate()
        click("取消")
        rotate()
        compose.onNodeWithText("验证器 6 位验证码").assertDoesNotExist()
    }

    @Test fun aboutPageShowsActualVersionOfflineLicenseAndGithubLink() {
        compose.onNodeWithText("设置").performClick()
        click("关于软件")
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
        compose.onNodeWithText(PROJECT_URL).assertHasClickAction()
        val license = controller.get().resources.openRawResource(R.raw.license).bufferedReader().use { it.readText() }
        assertTrue(license.startsWith("MIT License\n\nCopyright (c) 2026 chen"))
        assertTrue(license.contains("THE SOFTWARE IS PROVIDED \"AS IS\""))
        compose.onNodeWithText(license).assertExists()
        rotate()
        compose.onNodeWithText("关于软件").assertExists()
        val flags = controller.get().packageManager.getActivityInfo(controller.get().componentName, 0).configChanges
        assertTrue(flags and ActivityInfo.CONFIG_ORIENTATION != 0)
        assertTrue(flags and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
        click("GitHub 项目主页")
        val intent = shadowOf(controller.get()).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals(PROJECT_URL, intent.data.toString())
    }
}
