package com.homemeds.app

import java.net.URI
import java.net.URLDecoder
import java.time.YearMonth

data class ParsedCode(
    val kind: String, val raw: String, val productKey: String? = null, val gtin: String? = null,
    val traceCode: String? = null, val expiryDate: String? = null, val batchNumber: String? = null, val serialNumber: String? = null,
)

internal fun preferredMedicineCode(rawCodes: List<String>): String? {
    val codes = rawCodes.map(String::trim).filter(String::isNotEmpty).distinct()
    val traces = codes.filter { parseCode(it).traceCode != null }
    if (traces.map { parseCode(it).traceCode }.distinct().size > 1) return null
    return traces.firstOrNull() ?: codes.maxByOrNull {
        when (parseCode(it).kind) { "gs1" -> 2; "gtin" -> 1; else -> 0 }
    }
}

fun validGtin(s: String): Boolean {
    if (!Regex("^(\\d{8}|\\d{12,14})$").matches(s)) return false
    val sum = s.dropLast(1).reversed().mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 3 else 1 }.sum()
    return (10 - sum % 10) % 10 == s.last().digitToInt()
}

private fun gs1Date(s: String): String? = runCatching {
    val month = YearMonth.of(2000 + s.take(2).toInt(), s.substring(2, 4).toInt())
    month.atDay(s.takeLast(2).toInt().takeIf { it != 0 } ?: month.lengthOfMonth()).toString()
}.getOrNull()

fun parseCode(input: String): ParsedCode {
    val raw = input.trim().take(4096)
    val unknown = ParsedCode("unknown", raw)
    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
        return runCatching {
            val u = URI(raw)
            fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
            val params = (u.rawQuery ?: "").split('&').associate {
                val parts = it.split('=', limit = 2)
                decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }
            val parts = (listOf(decode(u.rawPath ?: "")) + params.values).joinToString(" ")
            val trace = Regex("(?:^|\\D)(\\d{20})(?!\\d)").find(parts)?.groupValues?.get(1)
            val gtin = params["gtin"] ?: Regex("/01/(\\d{14})").find(u.path ?: "")?.groupValues?.get(1)
            when {
                trace != null -> parseCode(trace).copy(raw = raw)
                gtin != null && validGtin(gtin) -> parseCode(gtin).copy(raw = raw)
                else -> unknown
            }
        }.getOrDefault(unknown)
    }
    if (Regex("^\\d{20}$").matches(raw)) return ParsedCode("trace", raw, raw.take(7), traceCode = raw)
    if (validGtin(raw)) {
        val gtin = raw.padStart(14, '0')
        return ParsedCode("gtin", raw, gtin, gtin)
    }
    var value = raw.replace(Regex("^\\][A-Za-z]\\d"), "").replace(Regex("\\((\\d{2})\\)"), "\u001d$1").trimStart('\u001d')
    if (!value.startsWith("01")) return unknown
    var result = ParsedCode("gs1", raw)
    while (value.isNotEmpty()) {
        value = value.trimStart('\u001d')
        if (value.length < 2) break
        val ai = value.take(2)
        value = value.drop(2)
        when (ai) {
            "01" -> {
                val gtin = value.take(14)
                if (gtin.length != 14 || !validGtin(gtin)) return unknown
                result = result.copy(gtin = gtin, productKey = gtin); value = value.drop(14)
            }
            "17", "11", "15" -> {
                if (!Regex("^\\d{6}").containsMatchIn(value)) return unknown
                if (ai == "17") result = result.copy(expiryDate = gs1Date(value.take(6)))
                value = value.drop(6)
            }
            "10", "21" -> {
                val end = value.indexOf('\u001d').takeIf { it >= 0 } ?: 20
                val field = value.take(end)
                result = if (ai == "10") result.copy(batchNumber = field) else result.copy(serialNumber = field)
                value = value.drop(field.length)
            }
            else -> break
        }
    }
    return if (result.gtin != null) result else unknown
}
