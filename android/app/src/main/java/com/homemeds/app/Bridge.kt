package com.homemeds.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Message bridge for `src/client/native.ts`. Requests are `{id, method, params}`;
 * replies are `{id, type: event|result|error}`. Only the site's main frame is served.
 */
class Bridge(private val activity: MainActivity) : WebViewCompat.WebMessageListener {
    private val main = Handler(Looper.getMainLooper())
    private val context = activity.applicationContext
    private var speech: Speech? = null

    override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        if (!isMainFrame || Config.origin(sourceOrigin) != Config.origin) return
        val request = message.data?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val id = request.optString("id").takeIf { it.isNotEmpty() && it.length <= 40 } ?: return
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        activity.lifecycleScope.launch {
            try {
                val result = handle(method, params, id) { send(replyProxy, JSONObject().put("id", id).put("type", "event").put("data", it)) }
                send(replyProxy, JSONObject().put("id", id).put("type", "result").put("data", result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(replyProxy, JSONObject().put("id", id).put("type", "error").put("message", userMessage(e)))
            }
        }
    }

    // Always queue, even from the main thread, so events keep their order before the result.
    private fun send(proxy: JavaScriptReplyProxy, message: JSONObject) {
        val text = message.toString()
        main.post { runCatching { proxy.postMessage(text) } }
    }

    private fun userMessage(e: Exception): String = when {
        e is UserError -> e.message!!
        e.message?.hasChinese() == true -> e.message!!
        e is IOException -> "网络连接失败，请检查网络后重试"
        else -> "手机功能暂不可用"
    }

    private fun speech() = speech ?: Speech(context).also { speech = it }

    private val reminderPermissions = buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private suspend fun handle(method: String, params: JSONObject, id: String, emit: (JSONObject) -> Unit): Any = when (method) {
        "crypto.publicKey" -> JSONObject().put("public_key", withContext(Dispatchers.IO) { DeviceKeys.publicKey() })
        "ai.open" -> {
            val sessionId = params.getString("session_id")
            val payload = withContext(Dispatchers.IO) { DeviceKeys.open(params.getJSONObject("credentials")) }
            NativeHttp.open(AiSession.parse(sessionId, payload))
            JSONObject.NULL
        }
        "ai.close" -> { NativeHttp.close(params.getString("session_id")); JSONObject.NULL }
        "http.fetch" -> { NativeHttp.fetch(id, params, emit); JSONObject.NULL }
        "http.abort" -> { NativeHttp.abort(params.getString("request_id")); JSONObject.NULL }
        "session.changed" -> {
            val group = if (params.isNull("group_id")) null else params.getString("group_id")
            Sync.sessionChanged(context, Prefs.Session(params.getString("user_id"), group, params.optBoolean("is_elderly")))
            JSONObject.NULL
        }
        "logout" -> { NativeHttp.closeAll(); Sync.logout(context); JSONObject.NULL }
        "calendar.status" -> Sync.status(context)
        "calendar.configure" -> { Sync.configure(context, params); Sync.run(context); Sync.status(context) }
        "calendar.sync" -> { Sync.run(context); Sync.status(context) }
        "calendar.requestPermissions" -> { activity.requestPermissions(reminderPermissions); Sync.run(context); Sync.status(context) }
        "calendar.openExactAlarmSettings" -> { activity.openExactAlarmSettings(); Sync.status(context) }
        "calendar.openBatterySettings" -> { activity.openAppSettings(); Sync.status(context) }
        "tts.available" -> JSONObject().put("available", speech().available())
        "tts.speak" -> { speech().speak(params.getString("text")); JSONObject.NULL }
        "tts.stop" -> { speech?.stop(); JSONObject.NULL }
        "voice.available" -> JSONObject().put("available", activity.voiceAvailable())
        "voice.listen" -> JSONObject().put("text", activity.listen() ?: throw UserError("没有识别到语音，请再说一次"))
        else -> throw UserError("手机应用版本过旧，请更新家药箱")
    }

    fun dispose() {
        NativeHttp.closeAll()
        speech?.shutdown()
        speech = null
    }
}
