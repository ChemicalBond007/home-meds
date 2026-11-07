package com.homemeds.app

import org.json.JSONObject

internal fun groupJoinCodeFromQr(raw: String): String? {
    if (raw.length > 4096) return null
    return runCatching {
        val data = JSONObject(raw)
        if (data.opt("type") != "home-meds.join" || data.opt("version") != 1) return null
        (data.opt("code") as? String)?.trim()?.takeIf { it.length in 1..100 }
    }.getOrNull()
}

internal fun preferredGroupJoinCode(rawCodes: List<String>): String? =
    rawCodes.mapNotNull(::groupJoinCodeFromQr).distinct().singleOrNull()

internal fun memberRegistrationBody(username: String, code: String, elderly: Boolean): JSONObject {
    val join = code.trim()
    if (join.isEmpty()) throw UserError("请先输入家庭分组码")
    if (join.length > 100) throw UserError("家庭分组码不能超过 100 个字符")
    val name = username.trim()
    if (name.length !in 2..40 || !Regex("^[\\p{L}\\p{N}_-]+$").matches(name))
        throw UserError("用户名须为 2–40 个文字、数字、下划线或短横线")
    return json("username" to name, "join_code" to join, "is_elderly" to elderly)
}
