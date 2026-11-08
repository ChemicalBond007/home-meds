package com.homemeds.app.ui

import android.Manifest
import android.app.Application
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScannerUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private var starts = 0
    private val scanned = mutableListOf<String>()

    @Before fun setup() { shadowOf(context).denyPermissions(Manifest.permission.CAMERA) }

    private fun homeEntry() {
        compose.setContent { HomeMedsTheme(false) {
            var scanning by remember { mutableStateOf(false) }
            Column {
                MedicineLookupEntry(true, { scanning = true }, {})
                if (scanning) ScannerContent({ scanning = false }, ScanMode.MEDICINE, {
                    starts++
                    throw IllegalStateException("Simulated decoder initialization failure")
                }) { scanned += it }
            }
        } }
        compose.onNodeWithText("扫码查药 / 入库").performClick()
        compose.onNodeWithText("扫描条码 / 追溯码").performClick()
    }

    @Test fun homeScannerOpensWithoutPermissionAndDoesNotInitializeDecoder() {
        homeEntry()
        compose.onNodeWithText("扫描药品条码 / 追溯码").assertExists()
        compose.onNodeWithText("允许相机扫码").assertExists()
        compose.runOnIdle { assertEquals(0, starts); assertTrue(scanned.isEmpty()) }
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("扫描药品条码 / 追溯码").assertDoesNotExist()
    }

    @Test fun noPermissionStillAllowsOneManualCodeSubmission() {
        homeEntry()
        compose.onNodeWithText("也可手动输入或粘贴").performTextInput(" 6901234567892 ")
        compose.onNodeWithText("使用输入的码").performScrollTo().performClick()
        compose.onNodeWithText("使用输入的码").performClick()
        compose.runOnIdle { assertEquals(listOf("6901234567892"), scanned); assertEquals(0, starts) }
    }

    @Test fun decoderInitializationFailureDoesNotCrashAndKeepsManualEntryUsable() {
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        homeEntry()
        compose.onNodeWithText("无法初始化扫码，请使用手动输入").assertExists()
        compose.onNodeWithText("也可手动输入或粘贴").performScrollTo().performTextInput("81234567890123456789")
        compose.onNodeWithText("使用输入的码").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(listOf("81234567890123456789"), scanned) }
    }

    @Test fun groupScannerFailureStillAllowsReturningToManualRegistration() {
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        var dismissed = false
        compose.setContent { HomeMedsTheme(false) {
            ScannerContent({ dismissed = true }, ScanMode.GROUP_JOIN, {
                throw IllegalStateException("Simulated decoder initialization failure")
            }) { scanned += it }
        } }
        compose.onNodeWithText("无法初始化扫码，请使用手动输入").assertExists()
        compose.onNodeWithText("返回手动输入分组码").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed); assertTrue(scanned.isEmpty()) }
    }

    @Test @Config(qualifiers = "w360dp-h640dp")
    fun previewAndManualControlsFitPhoneWithoutOverlap() {
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        homeEntry()
        val previewSize = compose.onNodeWithTag("scanner_preview").getUnclippedBoundsInRoot()
        assertEquals(4f / 3f, (previewSize.right - previewSize.left).value / (previewSize.bottom - previewSize.top).value, .02f)
        compose.onNodeWithText("使用输入的码").performScrollTo().assertIsDisplayed()
        val preview = compose.onNodeWithTag("scanner_preview").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithText("也可手动输入或粘贴").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("使用输入的码").fetchSemanticsNode().boundsInRoot
        assertTrue("preview must not cover input", preview.bottom <= input.top)
        assertTrue("input and button must be on separate rows", input.bottom <= button.top)
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land")
    fun landscapePreviewLeavesManualControlsReachableByScrolling() {
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        homeEntry()
        val preview = compose.onNodeWithTag("scanner_preview").getUnclippedBoundsInRoot()
        assertEquals("preview should fill width instead of shrinking", 4f / 3f,
            (preview.right - preview.left).value / (preview.bottom - preview.top).value, .02f)
        compose.onNodeWithText("也可手动输入或粘贴").performScrollTo().performTextInput("6901234567892")
        compose.onNodeWithText("使用输入的码").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("6901234567892"), scanned) }
    }

    @Test fun centeredScanLineAnimatesInsideTargetAndShowsGuidance() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HomeMedsTheme(false) {
            Box(Modifier.width(320.dp).height(240.dp)) { ScannerOverlay(ScanMode.MEDICINE) }
        } }
        val overlay = compose.onNodeWithTag("scanner_overlay").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("scanner_target").fetchSemanticsNode().boundsInRoot
        assertEquals(overlay.center.x, target.center.x, 1f)
        assertEquals(overlay.center.y, target.center.y, 1f)
        val initial = compose.onNodeWithTag("scanner_line").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(700)
        val moved = compose.onNodeWithTag("scanner_line").fetchSemanticsNode().boundsInRoot
        assertTrue("scan line should move vertically", moved.top > initial.top)
        assertTrue(moved.top >= target.top && moved.bottom <= target.bottom)
        compose.onNodeWithText("对准条码或追溯码，正在扫描…").assertIsDisplayed()
    }

    @Test fun inactiveCameraDoesNotShowAMisleadingScanningAnimation() {
        compose.setContent { HomeMedsTheme(false) {
            ScannerPreview(PreviewView(context), ScanMode.MEDICINE, active = false)
        } }
        compose.onNodeWithTag("scanner_overlay").assertDoesNotExist()
    }
}
