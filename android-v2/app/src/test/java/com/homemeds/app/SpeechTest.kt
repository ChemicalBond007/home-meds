package com.homemeds.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpeechTest {
    private val dispatcher = StandardTestDispatcher()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Engine(initialized: Boolean? = true) : SpeechEngine {
        override val ready = CompletableDeferred<Boolean>().apply { initialized?.let { complete(it) } }
        override var hasEngines = true
        var supported = setOf(Locale.SIMPLIFIED_CHINESE)
        var accepts = supported
        val probes = mutableListOf<Locale>()
        val selections = mutableListOf<Locale>()
        var result = TextToSpeech.SUCCESS
        val spoken = mutableListOf<String>()
        var shutdowns = 0
        var stops = 0
        override fun languageAvailable(locale: Locale): Int {
            probes += locale
            return if (locale in supported) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
        }
        override fun setLanguage(locale: Locale): Int {
            selections += locale
            return if (locale in accepts) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
        }
        override fun speak(text: String): Int { spoken += text; return result }
        override fun stop() { stops++ }
        override fun shutdown() { shutdowns++ }
    }

    @Test fun chineseLocalesAreTriedInOrderIncludingMandarinAndGenericChinese() {
        assertEquals(listOf("zh-CN", "zh", "zh-Hans-CN", "cmn-Hans-CN"), chineseLocales.map { it.toLanguageTag() })
        for (locale in chineseLocales) {
            val engine = Engine().apply { supported = setOf(locale); accepts = supported }
            selectChineseLanguage(engine)
            assertEquals(locale, engine.selections.last())
            assertEquals(chineseLocales.take(chineseLocales.indexOf(locale) + 1), engine.probes)
        }
    }

    @Test fun nonstandardLanguageReportsStillTryChineseAndDefaultVoice() = runTest(dispatcher) {
        val engine = Engine().apply { supported = emptySet(); accepts = emptySet() }
        val speech = Speech(context) { engine }
        speech.speak("中文朗读")
        assertEquals(chineseLocales, engine.selections)
        assertEquals(listOf("中文朗读"), engine.spoken)
        speech.shutdown()
    }

    @Test fun rejectedRegionalVoiceFallsBackToAnotherChineseLocale() {
        val engine = Engine().apply { supported = chineseLocales.toSet(); accepts = setOf(Locale.CHINESE) }
        selectChineseLanguage(engine)
        assertEquals(listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE), engine.selections)
    }

    @Test fun initializationFailureAndTimeoutRebindBeforeSpeaking() = runTest(dispatcher) {
        for (failed in listOf(Engine(false), Engine(null))) {
            val good = Engine()
            val engines = ArrayDeque(listOf(failed, good))
            val speech = Speech(context) { engines.removeFirst() }
            speech.speak("重新连接")
            assertEquals(1, failed.shutdowns)
            assertEquals(listOf("重新连接"), good.spoken)
            assertTrue(engines.isEmpty())
            speech.shutdown()
        }
    }

    @Test fun speakErrorReconnectsExactlyOnceAndChecksRetryReturnCode() = runTest(dispatcher) {
        val first = Engine().apply { result = TextToSpeech.ERROR }
        val second = Engine().apply { result = TextToSpeech.ERROR }
        val engines = ArrayDeque(listOf(first, second))
        val speech = Speech(context) { engines.removeFirst() }
        try { speech.speak("失败"); fail("Expected engine error") }
        catch (e: UserError) { assertEquals("手机没有可用的中文朗读引擎", e.message) }
        assertEquals(1, first.shutdowns)
        assertEquals(1, second.shutdowns)
        assertEquals(listOf("失败"), second.spoken)
        val restored = Engine()
        engines.add(restored)
        speech.speak("安装引擎后再试")
        assertEquals(listOf("安装引擎后再试"), restored.spoken)
        speech.shutdown()
    }

    @Test fun disconnectedEngineReconnectsAndSuccessfulEngineIsReused() = runTest(dispatcher) {
        val failed = Engine().apply { result = TextToSpeech.ERROR }
        val good = Engine()
        val engines = ArrayDeque(listOf(failed, good))
        val speech = Speech(context) { engines.removeFirst() }
        speech.speak("一")
        speech.speak("二")
        assertEquals(listOf("一", "二"), good.spoken)
        assertEquals(1, failed.shutdowns)
        speech.shutdown()
        assertEquals(1, good.shutdowns)
    }

    @Test fun noEnginesDoesNotAttemptSpeakingAndLaterInstallCanRecover() = runTest(dispatcher) {
        val absent = Engine().apply { hasEngines = false }
        val good = Engine()
        var installed = false
        val speech = Speech(context) { if (installed) good else absent }
        assertFalse(speech.available())
        assertTrue(absent.spoken.isEmpty())
        installed = true
        speech.speak("恢复")
        assertEquals(listOf("恢复"), good.spoken)
        speech.shutdown()
    }

    @Test fun stoppingDuringInitializationDoesNotSpeakAfterReturningFromBackground() = runTest(dispatcher) {
        val engine = Engine(null)
        val speech = Speech(context) { engine }
        val job = launch { speech.speak("不应播放") }
        runCurrent()
        speech.stop()
        engine.ready.complete(true)
        job.join()
        assertTrue(engine.spoken.isEmpty())
        speech.speak("再次点击")
        assertEquals(listOf("再次点击"), engine.spoken)
        speech.shutdown()
        speech.speak("已经关闭")
        assertEquals(1, engine.spoken.size)
    }

    private fun registerService(name: String = "test.speech"): ComponentName {
        val component = ComponentName(name, "$name.RecognitionService")
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent("android.speech.RecognitionService"),
            ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                packageName = component.packageName; this.name = component.className; exported = true
            } })
        return component
    }

    @Test fun recognitionIntentRequestsChineseAndConfiguredServiceIsPreferred() {
        val intent = speechRecognitionIntent()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertNull(speechRecognitionActivity(context))
        val component = registerService()
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", "missing/.Service")
        assertNull(speechRecognitionService(context))
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", component.flattenToString())
        assertEquals(component, speechRecognitionService(context))
    }

    @Test fun serviceOnlyRecognitionReturnsTextAndDestroysRecognizer() = runTest(dispatcher) {
        registerService()
        val speech = Speech(context)
        val result = async { speech.listen() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals("zh-CN", recognizer.lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        recognizer.triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("每天一次"))
        })
        assertEquals("每天一次", result.await())
        assertTrue(recognizer.isDestroyed)
        speech.shutdown()
    }

    @Test fun cancellationAndTimeoutReleaseMicrophoneAndAllowAnotherRecognition() = runTest(dispatcher) {
        registerService()
        val speech = Speech(context)
        val cancelled = launch { speech.listen() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val first = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        cancelled.cancelAndJoin()
        assertTrue(first.isDestroyed)
        val timedOut = async { runCatching { speech.listen() } }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val second = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        advanceTimeBy(60001)
        assertEquals("语音识别超时，请重试", timedOut.await().exceptionOrNull()?.message)
        assertTrue(second.isDestroyed)
        speech.shutdown()
    }

    @Test fun permissionErrorIsReportedAndRecognizerIsDestroyed() = runTest(dispatcher) {
        registerService()
        val speech = Speech(context)
        val result = async { runCatching { speech.listen() } }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        recognizer.triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals("请允许录音权限后重试语音输入", result.await().exceptionOrNull()?.message)
        assertTrue(recognizer.isDestroyed)
        speech.shutdown()
    }
}
