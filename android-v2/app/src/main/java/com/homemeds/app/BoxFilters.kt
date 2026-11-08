package com.homemeds.app

import org.json.JSONObject
import java.text.Collator
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Days ahead that count as "about to expire", shared with the home screen banner. */
const val EXPIRY_SOON_DAYS = 30L

/** User-chosen filters and order for the in-stock box list. Empty strings mean "any". */
data class BoxFilter(
    val status: String = "active",
    val warehouseId: String = "",
    val productId: String = "",
    val expiry: String = "",
    val opened: String = "",
    val sort: String = "expiry_asc",
) {
    /** Conditions beyond the default view, used for the filter button badge. */
    val activeCount get() = listOf(status != "active", warehouseId.isNotEmpty(), productId.isNotEmpty(),
        expiry.isNotEmpty(), opened.isNotEmpty()).count { it }
    val isDefault get() = this == BoxFilter()

    fun toJson(): JSONObject = json("status" to status, "warehouse_id" to warehouseId, "product_id" to productId,
        "expiry" to expiry, "opened" to opened, "sort" to sort)

    /** Drops ids that no longer exist in the family, e.g. a deleted warehouse or a switched family. */
    fun sanitize(warehouseIds: Collection<String>, productIds: Collection<String>) = copy(
        warehouseId = warehouseId.takeIf { it in warehouseIds }.orEmpty(),
        productId = productId.takeIf { it in productIds }.orEmpty(),
    )

    companion object {
        val statuses = listOf("active" to "在库", "empty" to "用尽", "discarded" to "报废", "" to "全部状态")
        val expiries = listOf("" to "全部有效期", "expired" to "已过期", "soon" to "$EXPIRY_SOON_DAYS 天内到期",
            "valid" to "未过期", "missing" to "未填写有效期")
        val openedStates = listOf("" to "全部", "opened" to "已开封", "sealed" to "未开封")
        val sorts = listOf("expiry_asc" to "有效期从早到晚", "expiry_desc" to "有效期从晚到早", "name" to "药名",
            "remaining_asc" to "余量从少到多", "remaining_desc" to "余量从多到少", "created_desc" to "最近入库",
            "warehouse" to "按药箱分组")

        fun parse(raw: String?): BoxFilter = runCatching {
            val o = JSONObject(raw!!)
            fun pick(key: String, allowed: List<Pair<String, String>>, fallback: String) =
                o.optString(key, fallback).takeIf { v -> allowed.any { it.first == v } } ?: fallback
            val d = BoxFilter()
            BoxFilter(pick("status", statuses, d.status), o.optString("warehouse_id"), o.optString("product_id"),
                pick("expiry", expiries, d.expiry), pick("opened", openedStates, d.opened), pick("sort", sorts, d.sort))
        }.getOrDefault(BoxFilter())
    }
}

/** Mirrors the card status: an active box with nothing left is shown and filtered as used up. */
fun boxState(item: StockItem): String = when (item.data.optString("status")) {
    "discarded" -> "discarded"
    "empty" -> "empty"
    else -> if (item.remaining <= 0) "empty" else "active"
}

private fun daysUntil(expiry: String, today: LocalDate): Long? =
    runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(expiry)) }.getOrNull()

fun matchesSearch(item: StockItem, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return listOfNotNull(item.name, item.trace, item.data.nullable("batch_number"), item.warehouse)
        .any { it.contains(q, ignoreCase = true) }
}

fun applyBoxFilter(stock: List<StockItem>, filter: BoxFilter, today: String, search: String = ""): List<StockItem> {
    val day = runCatching { LocalDate.parse(today) }.getOrNull()
    val kept = stock.filter { item ->
        (filter.status.isEmpty() || boxState(item) == filter.status) &&
            (filter.warehouseId.isEmpty() || item.data.optString("warehouse_id") == filter.warehouseId) &&
            (filter.productId.isEmpty() || item.productId == filter.productId) &&
            when (filter.opened) {
                "opened" -> item.data.optInt("opened") == 1
                "sealed" -> item.data.optInt("opened") != 1
                else -> true
            } &&
            when (filter.expiry) {
                "missing" -> item.expiry == null
                "expired" -> item.expiry?.let { it < today } == true
                "valid" -> item.expiry?.let { it >= today } == true
                "soon" -> item.expiry?.let { e -> day?.let { daysUntil(e, it) } }?.let { it in 0..EXPIRY_SOON_DAYS } == true
                else -> true
            } &&
            matchesSearch(item, search)
    }
    val collator = Collator.getInstance(Locale.CHINA)
    val byName = Comparator<StockItem> { a, b -> collator.compare(a.name, b.name) }
    // Boxes without an expiry date always go last so they are not mistaken for the earliest or latest.
    val expiryKnownFirst = compareBy<StockItem> { it.expiry == null }
    val order: Comparator<StockItem> = when (filter.sort) {
        "expiry_desc" -> expiryKnownFirst.thenByDescending { it.expiry }
        "name" -> byName.thenBy { it.expiry ?: "9999-99-99" }
        "remaining_asc" -> compareBy<StockItem> { it.remaining }
        "remaining_desc" -> compareByDescending<StockItem> { it.remaining }
        "created_desc" -> compareByDescending<StockItem> { it.data.optString("created_at") }
        "warehouse" -> Comparator<StockItem> { a, b -> collator.compare(a.warehouse, b.warehouse) }
            .then(expiryKnownFirst).thenBy { it.expiry }
        else -> expiryKnownFirst.thenBy { it.expiry }
    }
    return kept.sortedWith(order.then(byName).thenBy { it.id })
}
