package com.homemeds.app

import org.junit.Assert.*
import org.junit.Test

class BoxFiltersTest {
    private val today = "2026-11-08"
    private fun box(id: String, name: String, expiry: String?, warehouse: String = "w1", remaining: Double = 10.0,
        status: String = "active", opened: Int = 0, created: String = "2026-01-01 00:00:00", product: String = "p-$name") =
        StockItem(json("id" to id, "product_id" to product, "name" to name, "base_unit" to "片", "units_remaining" to remaining,
            "status" to status, "expiry_date" to expiry, "warehouse_id" to warehouse,
            "warehouse_name" to if (warehouse == "w1") "客厅药箱" else "卧室药箱", "opened" to opened, "created_at" to created,
            "batch_number" to "B-$id"))

    private val stock = listOf(
        box("a", "阿司匹林", "2027-05-31", created = "2026-03-01 08:00:00"),
        box("b", "布洛芬", "2026-11-20", warehouse = "w2", remaining = 3.0, opened = 1, created = "2026-10-01 08:00:00"),
        box("c", "阿司匹林", "2026-10-01", remaining = 6.0, product = "p-阿司匹林"),
        box("d", "维生素C", null, warehouse = "w2", remaining = 30.0),
        box("e", "感冒灵", "2026-12-01", status = "empty", remaining = 0.0),
        box("f", "感冒灵", "2028-01-01", status = "active", remaining = 0.0),
        box("g", "退烧贴", "2026-12-31", status = "discarded"),
    )
    private fun ids(filter: BoxFilter, search: String = "") = applyBoxFilter(stock, filter, today, search).map { it.id }

    @Test fun defaultShowsAvailableBoxesByEarliestExpiryWithMissingDatesLast() {
        assertEquals(listOf("c", "b", "a", "d"), ids(BoxFilter()))
    }

    @Test fun expiryDescendingStillKeepsMissingDatesLast() {
        assertEquals(listOf("a", "b", "c", "d"), ids(BoxFilter(sort = "expiry_desc")))
    }

    @Test fun filtersByWarehouseAndProduct() {
        assertEquals(listOf("b", "d"), ids(BoxFilter(warehouseId = "w2")))
        assertEquals(listOf("c", "a"), ids(BoxFilter(productId = "p-阿司匹林")))
        assertEquals(emptyList<String>(), ids(BoxFilter(warehouseId = "w2", productId = "p-阿司匹林")))
    }

    @Test fun statusTreatsActiveBoxWithNothingLeftAsUsedUp() {
        assertEquals(listOf("e", "f"), ids(BoxFilter(status = "empty")))
        assertEquals(listOf("g"), ids(BoxFilter(status = "discarded")))
        assertEquals(7, ids(BoxFilter(status = "")).size)
    }

    @Test fun expiryWindows() {
        assertEquals(listOf("c"), ids(BoxFilter(expiry = "expired")))
        assertEquals(listOf("b"), ids(BoxFilter(expiry = "soon")))
        assertEquals(listOf("b", "a"), ids(BoxFilter(expiry = "valid")))
        assertEquals(listOf("d"), ids(BoxFilter(expiry = "missing")))
        assertEquals("expiry on today counts as soon, not expired", listOf("t"),
            applyBoxFilter(listOf(box("t", "今日到期", today)), BoxFilter(expiry = "soon"), today).map { it.id })
    }

    @Test fun openedFilter() {
        assertEquals(listOf("b"), ids(BoxFilter(opened = "opened")))
        assertEquals(listOf("c", "a", "d"), ids(BoxFilter(opened = "sealed")))
    }

    @Test fun otherSortOrders() {
        assertEquals(listOf("b", "c", "a", "d"), ids(BoxFilter(sort = "remaining_asc")))
        assertEquals(listOf("d", "a", "c", "b"), ids(BoxFilter(sort = "remaining_desc")))
        assertEquals(listOf("b", "a"), ids(BoxFilter(sort = "created_desc")).take(2))
        assertEquals("grouped by warehouse, earliest expiry first inside each", listOf("c", "a", "b", "d"),
            ids(BoxFilter(sort = "warehouse")))
        assertEquals("same name sorts by expiry", listOf("c", "a"), ids(BoxFilter(sort = "name")).filter { it in setOf("a", "c") })
    }

    @Test fun searchMatchesNameBatchWarehouseAndTrace() {
        assertEquals(listOf("b"), ids(BoxFilter(), "布洛"))
        assertEquals(listOf("a"), ids(BoxFilter(), "b-a"))
        assertEquals(listOf("b", "d"), ids(BoxFilter(), "卧室"))
    }

    @Test fun parseRoundTripsAndRejectsUnknownValues() {
        val f = BoxFilter("", "w2", "p1", "soon", "opened", "remaining_asc")
        assertEquals(f, BoxFilter.parse(f.toJson().toString()))
        assertEquals(BoxFilter(), BoxFilter.parse(null))
        assertEquals(BoxFilter(), BoxFilter.parse("not json"))
        assertEquals(BoxFilter(), BoxFilter.parse("""{"status":"bogus","sort":"bogus","expiry":"x","opened":"y"}"""))
    }

    @Test fun sanitizeDropsIdsFromAnotherFamily() {
        val f = BoxFilter(warehouseId = "gone", productId = "p1").sanitize(listOf("w1"), listOf("p1"))
        assertEquals("", f.warehouseId)
        assertEquals("p1", f.productId)
        assertEquals(1, f.activeCount)
    }
}
