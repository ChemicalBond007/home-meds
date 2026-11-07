package com.homemeds.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegistrationUiTest {
    @get:Rule val compose = createComposeRule()
    private var submitted: JSONObject? = null
    private var busy by mutableStateOf(false)
    private fun form() {
        compose.setContent { MaterialTheme { Page("注册成员") {
            MemberRegistrationForm(busy, { submitted = it }) { dismiss, scanned ->
                Action("模拟扫描成功") { scanned("Scanned_-09") }
                Action("取消扫码", onClick = dismiss)
            }
        } } }
    }
    @Test fun codeComesFirstAndIsRequiredBeforeEnteringNameOrRegistering() {
        form()
        compose.onNodeWithText("用户名").assertIsNotEnabled()
        compose.onNodeWithText("注册并加入家庭").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("家庭分组码").performScrollTo().performTextInput(" Manual_-09 ")
        compose.onNodeWithText("用户名").performScrollTo().assertIsEnabled().performTextInput("新成员")
        compose.onNodeWithText("注册并加入家庭").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Manual_-09", submitted!!.getString("join_code")); assertEquals("新成员", submitted!!.getString("username")) }
    }
    @Test fun scanningOnlyFillsCodeAndCancellationPreservesManualInput() {
        form()
        compose.onNodeWithText("家庭分组码").performTextInput("Manual")
        compose.onNodeWithText("扫描分组码二维码").performScrollTo().performClick()
        compose.onNodeWithText("取消扫码").performScrollTo().performClick()
        compose.onNodeWithText("家庭分组码").assertTextContains("Manual")
        compose.onNodeWithText("扫描分组码二维码").performScrollTo().performClick()
        compose.onNodeWithText("模拟扫描成功").performScrollTo().performClick()
        compose.onNodeWithText("家庭分组码").assertTextContains("Scanned_-09")
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText("用户名").performTextInput("扫码成员")
        compose.onNodeWithText("注册并加入家庭").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Scanned_-09", submitted!!.getString("join_code")) }
    }
    @Test fun pendingRegistrationDisablesEditingAndScanner() {
        form()
        compose.runOnIdle { busy = true }
        compose.onNodeWithText("家庭分组码").assertIsNotEnabled()
        compose.onNodeWithText("扫描分组码二维码").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("注册并加入家庭").performScrollTo().assertIsNotEnabled()
    }
}
