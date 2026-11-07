package com.homemeds.app

import java.time.LocalDate
import java.time.YearMonth

internal data class MedicineExpiry(val text: String, val date: LocalDate, val monthOnly: Boolean)

/** Preserve printed precision; only the confirmed API value expands a month to its last day. */
internal fun medicineExpiry(value: String): MedicineExpiry? {
    if (value.isBlank()) return null
    var text = value.trim().map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("").replace(Regex("\\s"), "")
    val monthSuffix = text.endsWith("月")
    text = text.replace(Regex("[./．／－年]"), "-").replace("月", "-").removeSuffix("日")
    if (monthSuffix) text = text.removeSuffix("-")
    if (Regex("\\d{6}|\\d{8}").matches(text))
        text = text.take(4) + "-" + text.substring(4, 6) + if (text.length == 8) "-" + text.takeLast(2) else ""
    val match = Regex("(\\d{4})-(\\d{1,2})(?:-(\\d{1,2}))?").matchEntire(text)
        ?: throw UserError("有效期请填写 YYYY-MM-DD 或 YYYY-MM")
    return try {
        val year = match.groupValues[1].toInt()
        require(year in 1..9999)
        val month = YearMonth.of(year, match.groupValues[2].toInt())
        val day = match.groupValues[3]
        if (day.isEmpty()) MedicineExpiry(month.toString(), month.atEndOfMonth(), true)
        else month.atDay(day.toInt()).let { MedicineExpiry(it.toString(), it, false) }
    } catch (_: Exception) { throw UserError("有效期日期无效，请核对实物上的年月日") }
}
