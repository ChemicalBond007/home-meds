package com.homemeds.app

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/** Read-only calls to the Worker using the WebView's session cookie. */
object Backend {
    private const val SIGNED_OUT = "登录已失效，请打开家药箱重新登录"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()

    suspend fun get(path: String, groupId: String?, timeoutSeconds: Long = 20): String {
        val cookie = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(Config.baseUrl) }
        if (cookie.isNullOrEmpty()) throw ApiException(401, SIGNED_OUT)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${Config.baseUrl}/api$path")
                .header("Accept", "application/json")
                .header("Cookie", cookie)
                .header("X-HomeMeds-Client", "android")
                .apply { if (groupId != null) header("X-Group-Id", groupId) }
                .build()
            client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
                val body = r.body.string()
                if (r.code == 401) throw ApiException(401, SIGNED_OUT)
                if (!r.isSuccessful) throw ApiException(r.code, runCatching { JSONObject(body).getString("error") }.getOrNull() ?: "服务器返回 ${r.code}")
                body
            }
        }
    }
}
