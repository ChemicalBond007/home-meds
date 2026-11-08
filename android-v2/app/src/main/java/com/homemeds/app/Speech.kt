package com.homemeds.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val chineseLocales = listOf(
    Locale.SIMPLIFIED_CHINESE,
    Locale.CHINESE,
    Locale.forLanguageTag("zh-Hans-CN"),
    Locale.forLanguageTag("cmn-Hans-CN"),
)

internal fun speechRecognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
    .putExtra(RecognizerIntent.EXTRA_PROMPT, "请说药名、用量和服药时间")

internal fun appendRecognizedText(existing: String, recognized: String): String =
    if (recognized.isBlank()) existing.take(6000) else (existing + recognized.trim()).take(6000)

internal fun speechRecognitionActivity(context: Context): ComponentName? =
    context.packageManager.queryIntentActivities(speechRecognitionIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        .firstOrNull { it.activityInfo.exported }?.activityInfo?.let { ComponentName(it.packageName, it.name) }

internal fun speechRecognitionService(context: Context): ComponentName? {
    val services = context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
        .filter { it.serviceInfo.exported }
        .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    val configured = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        ?.let(ComponentName::unflattenFromString)
    return services.firstOrNull { it == configured } ?: services.firstOrNull()
}

internal interface SpeechEngine {
    val ready: CompletableDeferred<Boolean>
    val hasEngines: Boolean
    fun languageAvailable(locale: Locale): Int
    fun setLanguage(locale: Locale): Int
    fun speak(text: String): Int
    fun stop()
    fun shutdown()
}

private class AndroidSpeechEngine(context: Context) : SpeechEngine {
    override val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) }
    override val hasEngines get() = tts.engines.isNotEmpty()
    override fun languageAvailable(locale: Locale) = tts.isLanguageAvailable(locale)
    override fun setLanguage(locale: Locale) = tts.setLanguage(locale)
    override fun speak(text: String): Int {
        tts.setSpeechRate(0.85f)
        return tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH, null, "home-meds")
    }
    override fun stop() { tts.stop() }
    override fun shutdown() { tts.shutdown() }
}

internal fun selectChineseLanguage(engine: SpeechEngine) {
    for (locale in chineseLocales) {
        if (engine.languageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE &&
            engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) return
    }
    // Some ROMs report unsupported locales even when setLanguage or the default voice works.
    for (locale in chineseLocales) {
        if (engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) return
    }
}

class Speech internal constructor(context: Context, private val createEngine: (Context) -> SpeechEngine) {
    constructor(context: Context) : this(context, ::AndroidSpeechEngine)

    private val context = context.applicationContext
    private val mutex = Mutex()
    private var engine: SpeechEngine? = null
    private var closed = false
    private var generation = 0
    private var recognizer: SpeechRecognizer? = null
    private var cancelListening: (() -> Unit)? = null

    private fun discardEngine() {
        engine?.let {
            it.ready.complete(false)
            runCatching { it.shutdown() }
        }
        engine = null
    }

    private suspend fun boundEngine(): SpeechEngine? {
        val current = engine ?: createEngine(context).also { engine = it }
        return if (withTimeoutOrNull(5000) { current.ready.await() } == true && current.hasEngines) current else null
    }

    suspend fun available(): Boolean = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (closed) return@withLock false
            repeat(2) {
                if (boundEngine() != null) return@withLock true
                discardEngine()
            }
            false
        }
    }

    suspend fun speak(text: String) {
        val request = generation
        withContext(Dispatchers.Main.immediate) {
            mutex.withLock {
                if (closed || request != generation) return@withLock
                repeat(2) {
                    try {
                        val current = boundEngine()
                        if (closed || request != generation) return@withLock
                        if (current != null) {
                            selectChineseLanguage(current)
                            if (current.speak(text) == TextToSpeech.SUCCESS) return@withLock
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Rebind a disconnected or newly enabled engine once. */ }
                    discardEngine()
                }
                throw UserError("手机没有可用的中文朗读引擎")
            }
        }
    }

    suspend fun listen(): String = withContext(Dispatchers.Main.immediate) {
        if (closed) throw UserError("语音输入已关闭，请重新打开页面")
        if (recognizer != null) throw UserError("正在语音输入，请稍候")
        val service = speechRecognitionService(context)
        if (service == null && !SpeechRecognizer.isRecognitionAvailable(context))
            throw UserError("手机未安装语音识别服务，请使用文字输入")
        val current = if (service != null) SpeechRecognizer.createSpeechRecognizer(context, service)
            else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = current
        try {
            withTimeoutOrNull(60000) {
                suspendCancellableCoroutine { continuation ->
                    cancelListening = { continuation.cancel() }
                    current.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resumeWithException(UserError(when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许录音权限后重试语音输入"
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未听清，请重新说一遍"
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络连接失败，请稍后重试"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙，请稍后重试"
                                else -> "语音识别失败，请重试或使用文字输入"
                            }))
                        }
                        override fun onResults(results: Bundle?) {
                            if (continuation.isActive) continuation.resume(
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull { it.isNotBlank() } ?: "")
                        }
                    })
                    current.startListening(speechRecognitionIntent())
                }
            } ?: throw UserError("语音识别超时，请重试")
        } finally {
            cancelListening = null
            recognizer = null
            try { current.cancel() } finally { current.destroy() }
        }
    }

    fun stop() {
        generation++
        engine?.stop()
        cancelListening?.invoke()
    }

    fun shutdown() {
        closed = true
        stop()
        discardEngine()
    }
}
