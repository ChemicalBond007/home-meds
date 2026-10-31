package com.homemeds.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** A medication plan as returned by `GET /api/plans`. */
data class Plan(
    val id: String,
    val groupId: String,
    val userId: String,
    val username: String,
    val medicineName: String,
    val amount: Double,
    val unit: String,
    val times: List<String>,
    val frequency: String,
    val weekdays: List<Int>,
    val intervalDays: Int,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val mealRelation: String,
    val notes: String,
    val enabled: Boolean,
    val notifyFamily: Boolean,
)

class UserError(message: String) : Exception(message)

private val timePattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

private fun JSONObject.text(key: String) = if (isNull(key)) "" else getString(key)

// D1 stores these columns as JSON text, so accept either a string or an array.
private fun JSONObject.array(key: String): JSONArray = when (val v = opt(key)) {
    is JSONArray -> v
    is String -> if (v.isBlank()) JSONArray() else JSONArray(v)
    else -> JSONArray()
}

fun parsePlan(o: JSONObject): Plan {
    val times = o.array("times").let { a -> (0 until a.length()).map { a.getString(it) } }.filter { timePattern.matches(it) }.distinct()
    val weekdays = o.array("weekdays").let { a -> (0 until a.length()).map { a.getInt(it) } }.filter { it in 0..6 }
    return Plan(
        id = o.getString("id"),
        groupId = o.getString("group_id"),
        userId = o.getString("user_id"),
        username = o.text("username"),
        medicineName = o.getString("medicine_name"),
        amount = o.getDouble("amount"),
        unit = o.text("unit"),
        times = times,
        frequency = o.getString("frequency"),
        weekdays = weekdays,
        intervalDays = o.optInt("interval_days", 1).coerceAtLeast(1),
        startDate = LocalDate.parse(o.getString("start_date")),
        endDate = o.text("end_date").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
        mealRelation = o.text("meal_relation"),
        notes = o.text("notes"),
        enabled = o.optInt("enabled", 1) == 1,
        notifyFamily = o.optInt("notify_family", 0) == 1,
    )
}

/** Skips rows this app version cannot read instead of failing the whole sync. */
fun parsePlans(body: String): List<Plan> {
    val a = JSONArray(body)
    return (0 until a.length()).mapNotNull { runCatching { parsePlan(a.getJSONObject(it)) }.getOrNull() }
}

fun familyZone(groupsBody: String, groupId: String): ZoneId {
    val a = JSONArray(groupsBody)
    for (i in 0 until a.length()) {
        val g = a.getJSONObject(i)
        if (g.optString("id") == groupId) return runCatching { ZoneId.of(g.getString("timezone")) }.getOrDefault(DEFAULT_ZONE)
    }
    throw UserError("没有找到你所在的家庭，请重新登录")
}

val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

object Texts {
    fun amount(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    fun meal(relation: String) = when (relation) {
        "before" -> "饭前"
        "after" -> "饭后"
        "with" -> "随餐"
        else -> ""
    }
}
