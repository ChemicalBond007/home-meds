package com.homemeds.app.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.homemeds.app.appendRecognizedText
import com.homemeds.app.speechRecognitionIntent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechInputUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private var text by mutableStateOf("原有内容")
    private var error: String? = null
    private var visible by mutableStateOf(true)
    private lateinit var voice: ChatVoiceInput
    private var sends = 0

    private inner class Results : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var popupCode: Int? = null
        var permissionCode: Int? = null
        var popupIntent: Intent? = null
        var restricted = false
        var launches = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            when (contract) {
                is ActivityResultContracts.StartActivityForResult -> {
                    popupCode = requestCode
                    popupIntent = input as Intent
                    if (restricted) throw SecurityException("restricted system popup")
                }
                is ActivityResultContracts.RequestPermission -> {
                    assertEquals(Manifest.permission.RECORD_AUDIO, input)
                    permissionCode = requestCode
                }
                else -> error("Unexpected speech contract")
            }
        }
    }

    private fun screen(results: Results) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides results) {
                if (visible) {
                    voice = rememberChatVoiceInput({ text = appendRecognizedText(text, it) }, { error = it })
                    ChatComposer(text, { text = it }, emptyList(), emptyList(), false,
                        canSend = !voice.busy && text.isNotBlank(), photograph = {}, removePhoto = {},
                        voice = voice.start, send = { sends++ }, stop = {}, voiceActive = voice.busy)
                }
            }
        } }
    }

    @Suppress("DEPRECATION")
    private fun registerPopup() {
        shadowOf(context.packageManager).addResolveInfoForIntent(speechRecognitionIntent(),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply {
                packageName = "test.speech"; name = "test.speech.Popup"; exported = true
            } })
    }

    @Suppress("DEPRECATION")
    private fun registerService() {
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent("android.speech.RecognitionService"),
            ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                packageName = "test.speech"; name = "test.speech.Service"; exported = true
            } })
    }

    private fun recognized(value: String) {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value))
        })
    }

    @Test @Config(sdk = [29, 35])
    fun popupIsPreferredWithoutMicrophonePermissionAndAppendsToLatestInput() {
        registerPopup()
        registerService()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val results = Results()
        screen(results)
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.onNodeWithContentDescription("语音输入").assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(results.permissionCode)
            assertNull(results.popupIntent!!.component)
            assertNull(results.popupIntent!!.`package`)
            text += "继续键入"
            results.dispatchResult(results.popupCode!!, ActivityResult(Activity.RESULT_OK,
                Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(" 语音结果 "))))
        }
        compose.onNodeWithTag("chat_input").assertTextContains("原有内容继续键入语音结果")
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, sends); assertNull(error) }
    }

    @Test fun cancellingPopupDoesNotStartNativeListeningOrRequestPermission() {
        registerPopup()
        registerService()
        val results = Results()
        screen(results)
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.runOnIdle { results.dispatchResult(results.popupCode!!, ActivityResult(Activity.RESULT_CANCELED, null)) }
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled()
        compose.runOnIdle { assertNull(results.permissionCode); assertEquals("原有内容", text); assertNull(error) }
    }

    @Test @Config(sdk = [29, 35])
    fun serviceOnlyRequestsPermissionThenAppendsToTextEditedWhileListening() {
        registerService()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val results = Results()
        screen(results)
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.runOnIdle {
            assertNull(results.popupCode)
            assertNotNull(results.permissionCode)
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            results.dispatchResult(results.permissionCode!!, true)
        }
        compose.onNodeWithContentDescription("语音输入").assertIsNotEnabled()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        compose.runOnIdle { text += "继续键入" }
        compose.runOnIdle { recognized("每天一次") }
        compose.onNodeWithTag("chat_input").assertTextContains("原有内容继续键入每天一次")
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, sends); assertNull(error) }
    }

    @Test fun permissionDenialPreservesInputAndAllowsRetry() {
        registerService()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val results = Results()
        screen(results)
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.runOnIdle { results.dispatchResult(results.permissionCode!!, false) }
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled()
        compose.runOnIdle {
            assertEquals("原有内容", text)
            assertTrue(error!!.contains("录音权限"))
            assertFalse(voice.busy)
        }
    }

    @Test fun restrictedPopupFallsBackToServiceAndReleasesMicrophoneWhenCancelled() {
        registerPopup()
        registerService()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val results = Results().apply { restricted = true }
        screen(results)
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.runOnIdle {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(voice.listening)
            voice.start()
            assertEquals(1, results.launches)
            voice.cancel()
        }
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isDestroyed)
            assertEquals("原有内容", text)
            assertNull(error)
        }
    }

    @Test fun popupFailureResultFallsBackAndRemovingConversationDestroysRecognizer() {
        registerPopup()
        registerService()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val results = Results()
        screen(results)
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.runOnIdle { results.dispatchResult(results.popupCode!!, ActivityResult(Activity.RESULT_FIRST_USER, null)) }
        compose.runOnIdle {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(voice.listening)
            visible = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isDestroyed) }
    }

    @Test fun missingSpeechServicesReportsClearErrorWithoutRequestingMicrophone() {
        val results = Results()
        screen(results)
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled()
        compose.runOnIdle {
            assertEquals("手机未安装语音识别服务，请使用文字输入", error)
            assertEquals(0, results.launches)
            assertEquals("原有内容", text)
        }
    }
}
