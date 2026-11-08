package com.homemeds.app.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatMarkdownUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reportedListShowsBulletAndBoldLabelWithoutLiteralAsterisks() {
        compose.setContent { HomeMedsTheme(false) { ChatMarkdown("* **药品**：演示药品\n* **备注**：核对实物") } }
        compose.onAllNodesWithText("•").assertCountEquals(2)
        val label = compose.onNodeWithText("药品：演示药品").assertExists()
        val styled = label.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(styled.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == 2 })
        compose.onNodeWithText("* **药品**：演示药品").assertDoesNotExist()
        compose.onNodeWithText("备注：核对实物").assertExists()
    }

    @Test fun streamUpdatesUseSameFormattingAndKeepLiteralCode() {
        var source by mutableStateOf("* **药")
        compose.setContent { HomeMedsTheme(false) { ChatMarkdown(source) } }
        compose.runOnIdle { source = "* **药品**：演示\n\n```\n* **代码原样**\n```" }
        compose.onNodeWithText("药品：演示").assertExists()
        compose.onNodeWithText("* **代码原样**").assertExists()
    }

    @Test fun tablesAndCodeStayInsideNarrowViewportAndDoNotOpenResourcesDuringRendering() {
        val opened = mutableListOf<String>()
        compose.setContent { HomeMedsTheme(false) {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                Box(Modifier.width(320.dp).testTag("markdown_frame")) {
                    ChatMarkdown("| 药品 | 数量 | 备注 |\n| --- | --- | --- |\n| 演示药品 | 10 | 核对实物 |\n\n" +
                        "[公开说明书](https://example.com/info)\n\n![药盒](https://example.com/image.png)\n\n<script>alert('test')</script>")
                }
            }
        } }
        compose.onNodeWithText("药品").assertExists()
        compose.onNodeWithText("数量").assertExists()
        compose.onNodeWithText("[图片：药盒]").assertExists()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }
}
