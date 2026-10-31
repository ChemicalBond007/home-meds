package com.homemeds.app.net

import com.homemeds.app.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** DNS and every redirect are checked. Credential-bearing requests never redirect. */
class NativeHttp(private val session: AiSession, timeoutSeconds: Int) {
    private val client = OkHttpClient.Builder().dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !UrlPolicy.isPublicAddress(it) }) throw UnknownHostException("只允许访问公网地址")
        }
    }).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS).build()

    suspend fun <T> request(url: String, body: JSONObject? = null, service: String? = null, anthropic: Boolean = false,
        block: suspend (Response) -> T): T {
        if (session.expired) throw UserError("AI 会话已过期，请重新发送")
        val target = UrlPolicy.publicUrl(url)
        if (!target.isHttps) throw UserError("手机端只读取 HTTPS 网页")
        val req = Request.Builder().url(target).header("Accept", "application/json").apply {
            if (service != null) {
                val key = KeyPolicy.resolve("hm-key:${session.id}:$service", target, session)
                if (anthropic) { header("x-api-key", key); header("anthropic-version", "2023-06-01") }
                else header("Authorization", "Bearer $key")
            }
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        return client.newCall(req).consume { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "AI 或搜索接口返回 ${response.code}")
            block(response)
        }
    }

    suspend fun json(url: String, body: JSONObject? = null, service: String? = null, anthropic: Boolean = false) =
        request(url, body, service, anthropic) { response ->
            val source = response.body.source()
            if (source.request(2L * 1024 * 1024 + 1)) throw UserError("AI 响应超过 2MB 限制")
            JSONObject(source.readUtf8())
        }

    suspend fun page(raw: String): JSONObject {
        var url = raw
        repeat(5) {
            var redirect: String? = null
            var result: JSONObject? = null
            // No keys are ever attached to public pages.
            val target = UrlPolicy.publicUrl(url)
            val request = Request.Builder().url(target).header("Accept", "text/html,text/plain,application/json").build()
            if (!target.isHttps) throw UserError("手机端只读取 HTTPS 网页")
            client.newCall(request).consume { r ->
                if (r.code in 300..399) redirect = r.header("Location")?.let { target.resolve(it)?.toString() }
                else {
                    if (!r.isSuccessful) throw UserError("网页返回 ${r.code}")
                    if (!Regex("text/|application/(json|xhtml)", RegexOption.IGNORE_CASE).containsMatchIn(r.header("Content-Type").orEmpty()))
                        throw UserError("只读取文字网页")
                    val source = r.body.source()
                    if (source.request(2L * 1024 * 1024 + 1)) throw UserError("网页超过 2MB 限制")
                    val text = source.readUtf8().replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").take(20000)
                    result = com.homemeds.app.json("url" to target.toString(), "content" to text)
                }
            }
            result?.let { return it }
            url = redirect ?: throw UserError("网页重定向无效")
        }
        throw UserError("网页重定向过多")
    }
}
