package com.homemeds.app.net

import com.homemeds.app.*
import okhttp3.*
import org.json.JSONObject
import java.net.Proxy
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Credential-free public pages only; DNS and every redirect are checked. */
class NativeHttp(timeoutSeconds: Int) {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !UrlPolicy.isPublicAddress(it) }) throw UnknownHostException("只允许访问公网地址")
        }
    }).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS).build()

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
