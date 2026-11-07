package com.homemeds.app.net

import com.homemeds.app.*
import android.content.Context
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

internal suspend fun <T> Call.consume(block: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
    coroutineScope {
        val guard = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { this@consume.cancel() }
        }
        try { execute().use { block(it) } }
        catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
        finally { guard.cancel() }
    }
}

fun decodeJson(text: String): Any = JSONTokener(text).nextValue().also {
    if (it !is JSONObject && it !is JSONArray) throw IOException("服务器没有返回有效 JSON")
}

/** Framing shared by backend and provider streams, including CRLF and multiline data. */
suspend fun readSse(reader: BufferedReader, emit: suspend (String, String) -> Unit) {
    var event = "message"
    val data = StringBuilder()
    var bytes = 0
    suspend fun dispatch() {
        if (data.isNotEmpty()) emit(event, data.toString().removeSuffix("\n"))
        data.clear(); event = "message"
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = reader.readLine() ?: break
        bytes += line.toByteArray().size + 1
        if (bytes > 2 * 1024 * 1024) throw IOException("流式响应超过 2MB 限制")
        when {
            line.isEmpty() -> dispatch()
            line.startsWith("event:") -> event = line.substring(6).removePrefix(" ")
            line.startsWith("data:") -> data.append(line.substring(5).removePrefix(" ")).append('\n')
        }
    }
    dispatch()
}

class NativeApi(
    val baseUrl: String,
    private val cookie: () -> String?,
    private val saveCookie: (String?) -> Unit,
    private val activeGroup: () -> String?,
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
) {
    private fun request(method: String, path: String, body: JSONObject?, groupId: String?, state: String? = null): Request {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid API path" }
        return Request.Builder().url("$baseUrl/api$path").header("Accept", "application/json")
            .header("X-HomeMeds-Client", "android").apply {
                if (method !in setOf("GET", "HEAD")) header("Origin", baseUrl)
                cookie()?.takeIf { it.isNotBlank() }?.let { header("Cookie", "hm_session=$it") }
                groupId?.let { header("X-Group-Id", it) }
                state?.let { header("X-HomeMeds-State", it) }
                method(method, if (method in setOf("GET", "HEAD")) null else
                    (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
    }

    private fun capture(response: Response) {
        val sentCookie = response.request.header("Cookie")?.removePrefix("hm_session=")
        // Ignore delayed responses from an account that has since signed out or changed.
        if (cookie() != sentCookie) return
        response.headers("Set-Cookie").forEach { raw ->
            Cookie.parse(response.request.url, raw)?.takeIf { it.name == "hm_session" }?.let {
                saveCookie(it.value.takeIf { value -> value.isNotBlank() && it.expiresAt > System.currentTimeMillis() })
            }
        }
    }

    private fun error(response: Response, text: String): Nothing {
        val message = runCatching { JSONObject(text).getString("error") }.getOrNull()
            ?: if (response.code == 401) "登录已失效，请重新登录" else "请求失败（${response.code}）"
        if (response.code == 401 && cookie() == response.request.header("Cookie")?.removePrefix("hm_session=")) saveCookie(null)
        throw ApiException(response.code, message)
    }

    suspend fun raw(method: String, path: String, body: JSONObject? = null, groupId: String? = activeGroup(), timeout: Long = 30, state: String? = null): String {
        val call = http.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).build().newCall(request(method, path, body, groupId, state))
        return call.consume { response ->
            capture(response)
            val source = response.body.source()
            if (source.request(2L * 1024 * 1024 + 1)) throw IOException("响应超过 2MB 限制")
            val text = source.readUtf8()
            if (!response.isSuccessful) error(response, text)
            decodeJson(text)
            text
        }
    }
    suspend fun obj(method: String, path: String, body: JSONObject? = null, groupId: String? = activeGroup(), state: String? = null) =
        JSONObject(raw(method, path, body, groupId, state = state))
    suspend fun array(path: String, groupId: String? = activeGroup()) = JSONArray(raw("GET", path, groupId = groupId))

    suspend fun sse(path: String, body: JSONObject, groupId: String? = activeGroup(), emit: suspend (String, Any) -> Unit) {
        val call = http.newBuilder().readTimeout(150, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
            .newCall(request("POST", path, body, groupId).newBuilder().header("Accept", "text/event-stream").build())
        call.consume { response ->
            capture(response)
            if (!response.isSuccessful) error(response, response.body.string().take(4000))
            if (!response.header("Content-Type").orEmpty().startsWith("text/event-stream")) throw IOException("服务器未返回事件流")
            var finished = false
            readSse(response.body.charStream().buffered()) { event, data ->
                val value = JSONTokener(data).nextValue()
                if (event == "error") throw UserError(value.toString())
                if (event == "done") finished = true
                emit(event, value)
            }
            if (!finished) throw IOException("对话连接提前中断，请重试")
        }
    }
}

object ApiClient {
    private lateinit var client: NativeApi
    private lateinit var prefs: Prefs
    internal lateinit var repository: AppRepository
        private set
    fun init(context: Context, api: NativeApi? = null) {
        prefs = Prefs(context)
        client = api ?: NativeApi(Config.baseUrl, { prefs.sessionCookie }, { prefs.sessionCookie = it }, { prefs.activeGroupId })
        repository = AppRepository(client)
    }
    suspend fun raw(method: String, path: String, body: JSONObject? = null, groupId: String? = prefs.activeGroupId, timeout: Long = 30) =
        client.raw(method, path, body, groupId, timeout)
    suspend fun request(method: String, path: String, body: JSONObject? = null, groupId: String? = prefs.activeGroupId) = client.obj(method, path, body, groupId)
    suspend fun array(path: String, groupId: String? = prefs.activeGroupId) = client.array(path, groupId)
    suspend fun sse(path: String, body: JSONObject, groupId: String?, emit: suspend (String, Any) -> Unit) = client.sse(path, body, groupId, emit)
}
