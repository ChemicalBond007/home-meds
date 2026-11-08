package com.homemeds.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet6Address
import java.net.InetAddress

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
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4 && (b[0] == 0 || b[0] >= 224 || b[0] == 100 && b[1] in 64..127 ||
            b[0] == 198 && b[1] in 18..19 || b[0] == 192 && b[1] == 0 ||
            b[0] == 198 && b[1] == 51 && b[2] == 100 || b[0] == 203 && b[1] == 0 && b[2] == 113)) return false
        if (b.size == 16 && ((b[0] and 0xe0) != 0x20 || b.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8))) return false
        return true
    }
}

/** Validate before encoding, so IDs cannot alter the API route. */
fun pathSegment(id: String?): String {
    if (id == null || !Regex("^[A-Za-z0-9_-]{1,100}$").matches(id)) throw UserError("编号格式无效，请刷新后重试")
    return okhttp3.HttpUrl.Builder().scheme("https").host("path.invalid").addPathSegment(id).build().encodedPath.drop(1)
}
