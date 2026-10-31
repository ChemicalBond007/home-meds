package com.homemeds.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Same rules as `publicUrl` in `src/shared/ai-http.ts`. */
object UrlPolicy {
    private val ipv4 = Regex("^\\d[\\d.]*$")
    private val blockedSuffix = Regex("\\.(localhost|local|internal|test|invalid|onion)$")

    fun publicUrl(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw UserError("只允许公开的 HTTP/HTTPS 网页地址")
        val host = url.host.lowercase()
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443) ||
            !host.contains('.') || ipv4.matches(host) || host.contains(':') || host.contains('[') ||
            blockedSuffix.containsMatchIn(host) || host == "localhost"
        ) throw UserError("只允许公开的 HTTP/HTTPS 网页地址")
        return url
    }

    /** Applied to DNS answers so a public name cannot point the phone at its own network. */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        if (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc) return false
        return true
    }
}

/** Provider keys unwrapped on this phone. Never sent to the page. */
data class AiSession(
    val id: String,
    val expiresAt: Long,
    val llmKey: String,
    val llmBase: HttpUrl,
    val tavilyKey: String?,
    val tavilyBase: HttpUrl,
) {
    val expired get() = System.currentTimeMillis() >= expiresAt

    override fun toString() = "AiSession($id)"

    companion object {
        fun parse(sessionId: String, payload: JSONObject): AiSession {
            if (payload.optString("session_id") != sessionId) throw UserError("AI 会话无效，请重新发送")
            val llm = payload.getString("llm_base_url").toHttpUrlOrNull()?.takeIf { it.isHttps }
                ?: throw UserError("LLM 接口必须使用 HTTPS")
            val tavily = (if (payload.isNull("tavily_url")) "https://api.tavily.com" else payload.getString("tavily_url")).toHttpUrl()
            return AiSession(
                id = sessionId,
                expiresAt = payload.getLong("expires_at"),
                llmKey = payload.getString("llm_key"),
                llmBase = llm,
                tavilyKey = if (payload.isNull("tavily_key")) null else payload.getString("tavily_key").ifEmpty { null },
                tavilyBase = tavily,
            )
        }
    }
}

/**
 * The page refers to keys as `hm-key:<session>:llm|tavily`. A reference is only
 * resolved for HTTPS requests under the configured base URL of that service.
 */
object KeyPolicy {
    private val ref = Regex("hm-key:([A-Za-z0-9-]+):(llm|tavily)")

    fun hasRef(value: String) = value.contains("hm-key:")

    fun resolve(value: String, url: HttpUrl, session: AiSession): String {
        val resolved = ref.replace(value) { m ->
            if (m.groupValues[1] != session.id) throw UserError("密钥引用无效，请重新发送")
            when (m.groupValues[2]) {
                "llm" -> if (within(url, session.llmBase)) session.llmKey else throw UserError("密钥只能发送给后台配置的 AI 服务")
                else -> if (within(url, session.tavilyBase)) session.tavilyKey ?: throw UserError("请管理员先配置 Tavily 密钥")
                else throw UserError("密钥只能发送给后台配置的搜索服务")
            }
        }
        if (ref.replace(value, "").contains("hm-key:")) throw UserError("密钥引用无效，请重新发送")
        return resolved
    }

    fun within(url: HttpUrl, base: HttpUrl): Boolean {
        if (!url.isHttps || !base.isHttps || url.host != base.host || url.port != base.port) return false
        val prefix = base.encodedPath.trimEnd('/')
        return prefix.isEmpty() || url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")
    }
}

/** Opens `sealForDevice` output from `src/worker/device-crypto.ts`. */
object Sealed {
    const val ALG = "RSA-OAEP-SHA1+A256GCM"
    private val oaep = OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    fun open(privateKey: PrivateKey, sealed: JSONObject): String {
        if (sealed.optString("alg") != ALG) throw UserError("服务器返回的凭据格式不受支持，请更新家药箱")
        val b64 = Base64.getDecoder()
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").apply { init(Cipher.DECRYPT_MODE, privateKey, oaep) }
        val raw = rsa.doFinal(b64.decode(sealed.getString("wrapped_key")))
        try {
            if (raw.size != 32) throw UserError("服务器返回的凭据无效")
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, b64.decode(sealed.getString("iv"))))
            return String(aes.doFinal(b64.decode(sealed.getString("ciphertext"))), Charsets.UTF_8)
        } finally {
            raw.fill(0)
        }
    }
}
