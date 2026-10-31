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
    val productId: String? = null,
    val preferredStockItemId: String? = null,
    val lastStockItemId: String? = null,
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
        productId = o.nullable("product_id"),
        preferredStockItemId = o.nullable("preferred_stock_item_id"),
        lastStockItemId = o.nullable("last_stock_item_id"),
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

fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

data class User(val id: String, val username: String, val role: String, val groupId: String?, val elderly: Boolean) {
    val admin get() = role == "admin"
    companion object {
        fun parse(o: JSONObject) = User(o.getString("id"), o.getString("username"), o.getString("role"), o.nullable("group_id"), o.optInt("is_elderly") == 1)
    }
}

data class FamilyGroup(val id: String, val name: String, val timezone: String, val joinCode: String?, val mealTimes: JSONObject) {
    companion object {
        fun parse(o: JSONObject) = FamilyGroup(o.getString("id"), o.getString("name"), o.optString("timezone", "Asia/Shanghai"),
            o.nullable("join_code"), when(val m = o.opt("meal_times")) { is JSONObject -> m; is String -> JSONObject(m); else -> JSONObject() })
    }
}

data class Product(val data: JSONObject) {
    val id get() = data.getString("id")
    val name get() = data.getString("name")
    val unit get() = data.getString("base_unit")
    val size get() = data.getDouble("units_per_package")
}

data class StockItem(val data: JSONObject) {
    val id get() = data.getString("id")
    val productId get() = data.getString("product_id")
    val name get() = data.optString("name")
    val unit get() = data.optString("base_unit")
    val warehouse get() = data.optString("warehouse_name")
    val expiry get() = data.nullable("expiry_date")
    val trace get() = data.nullable("trace_code")
    val remaining get() = data.getDouble("units_remaining")
    val version get() = data.optInt("metadata_version")
    val available get() = data.optString("status") == "active" && remaining > 0
}

data class DoseLog(val data: JSONObject) {
    val id get() = data.getString("id")
}
data class Todo(val plan: Plan, val date: String, val time: String, val state: String, val log: DoseLog?) {
    companion object {
        fun parse(o: JSONObject) = Todo(parsePlan(o.getJSONObject("plan")), o.getString("local_date"),
            o.getString("scheduled_time"), o.getString("state"), o.optJSONObject("log")?.let(::DoseLog))
    }
}
data class AISettings(val data: JSONObject)
data class UsageLog(val data: JSONObject)

fun Plan.body(): JSONObject = json(
    "user_id" to userId, "product_id" to productId, "preferred_stock_item_id" to preferredStockItemId,
    "medicine_name" to medicineName, "amount" to amount, "unit" to unit, "times" to JSONArray(times),
    "frequency" to frequency, "weekdays" to JSONArray(weekdays), "interval_days" to intervalDays,
    "start_date" to startDate.toString(), "end_date" to endDate?.toString(), "meal_relation" to mealRelation,
    "notes" to notes, "enabled" to enabled, "notify_family" to notifyFamily,
)
