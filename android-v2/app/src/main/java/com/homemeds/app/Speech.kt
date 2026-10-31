package com.homemeds.app

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class Speech(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) }

    suspend fun available(): Boolean {
        if (withTimeoutOrNull(5000) { ready.await() } != true) return false
        return tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
    }

    suspend fun speak(text: String) {
        if (!available()) throw UserError("手机没有可用的中文朗读引擎")
        tts.language = Locale.SIMPLIFIED_CHINESE
        tts.setSpeechRate(0.85f)
        tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH, null, "home-meds")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
