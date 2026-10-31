package com.homemeds.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * HTTP for the phone-side AI loop. Requests need an open AI session; keys are
 * injected here from `hm-key:` references, and responses stream back as chunks.
 */
object NativeHttp {
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val CHUNK = 16 * 1024
    private val sessions = ConcurrentHashMap<String, AiSession>()
    private val calls = ConcurrentHashMap<String, Pair<String, Call>>()
    private val dropped = setOf(
        "host", "cookie", "cookie2", "content-length", "connection", "keep-alive", "transfer-encoding", "te", "trailer",
        "upgrade", "accept-encoding", "origin", "referer", "proxy-authorization",
    )

    private object PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            if (all.isEmpty() || all.any { !UrlPolicy.isPublicAddress(it) }) throw UnknownHostException("只允许访问公网地址")
            return all
        }
    }

    private class PolicyException(message: String) : IOException(message)

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(PublicDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                try {
                    UrlPolicy.publicUrl(chain.request().url.toString())
                } catch (e: UserError) {
                    throw PolicyException(e.message!!)
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    fun open(session: AiSession) {
        if (session.expired) throw UserError("AI 会话已过期，请重新发送")
        sessions.values.removeIf { it.expired }
        sessions[session.id] = session
    }

    fun close(sessionId: String) {
        sessions.remove(sessionId)
        calls.entries.filter { it.value.first == sessionId }.forEach { calls.remove(it.key)?.second?.cancel() }
    }

    fun closeAll() {
        sessions.clear()
        calls.values.forEach { it.second.cancel() }
        calls.clear()
    }

    fun abort(requestId: String) {
        calls.remove(requestId)?.second?.cancel()
    }

    suspend fun fetch(requestId: String, params: JSONObject, emit: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val session = sessions[params.getString("session_id")]?.takeIf { !it.expired } ?: throw UserError("AI 会话已结束，请重新发送")
        val url = UrlPolicy.publicUrl(params.getString("url"))
        val method = params.optString("method", "GET").uppercase()
        if (method !in setOf("GET", "POST")) throw UserError("不支持的请求方式")
        val headers = Headers.Builder()
        var usesKey = false
        params.optJSONObject("headers")?.let { h ->
            for (name in h.keys()) {
                val lower = name.lowercase()
                if (lower in dropped) continue
                var value = h.getString(name)
                if (KeyPolicy.hasRef(value)) {
                    value = KeyPolicy.resolve(value, url, session)
                    usesKey = true
                }
                headers.add(lower, value)
            }
        }
        val body = if (params.isNull("body")) null else params.getString("body")
        if (body != null && method == "GET") throw UserError("GET 请求不能带内容")
        val type = (headers["content-type"] ?: "text/plain;charset=UTF-8").toMediaTypeOrNull()
        val request = Request.Builder()
            .url(url)
            .headers(headers.build())
            .method(method, if (method == "POST") (body ?: "").toByteArray().toRequestBody(type) else null)
            .build()
        // Never follow redirects while a key is attached; the page decides where to go.
        val follow = params.optString("redirect", "follow") == "follow" && !usesKey
        val call = client.newBuilder().followRedirects(follow).followSslRedirects(follow).build().newCall(request)
        calls[requestId] = session.id to call
        try {
            call.execute().use { response ->
                val head = JSONObject()
                for (name in response.headers.names()) {
                    if (!name.equals("set-cookie", ignoreCase = true)) head.put(name.lowercase(), response.headers.values(name).joinToString(", "))
                }
                emit(JSONObject().put("kind", "head").put("status", response.code).put("headers", head))
                val input = response.body.byteStream()
                val buffer = ByteArray(CHUNK)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw UserError("响应内容过大")
                    emit(JSONObject().put("kind", "chunk").put("data", Base64.getEncoder().encodeToString(buffer.copyOf(n))))
                }
            }
        } catch (e: IOException) {
            throw UserError(
                when {
                    call.isCanceled() -> "请求已取消"
                    e is PolicyException -> e.message!!
                    e is UnknownHostException -> if (e.message?.hasChinese() == true) e.message!! else "无法连接 ${url.host}"
                    e is InterruptedIOException -> "连接 ${url.host} 超时"
                    e is SSLException -> "${url.host} 的安全连接失败"
                    e.message?.contains("CLEARTEXT") == true -> "手机端只读取 HTTPS 网页"
                    else -> "网络请求失败，请检查网络后重试"
                },
            )
        } finally {
            calls.remove(requestId)
        }
    }
}
