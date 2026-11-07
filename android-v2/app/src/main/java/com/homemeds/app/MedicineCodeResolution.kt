package com.homemeds.app

import org.json.JSONObject

internal fun knownTraceBox(code: String, stock: List<StockItem>): StockItem? =
    parseCode(code).traceCode?.let { trace -> stock.firstOrNull { it.trace == trace } }

/** An exact box wins over missing or stale product-prefix mappings, including exhausted boxes. */
internal fun resolveMedicineCode(data: JSONObject, raw: String, products: List<Product>, stock: List<StockItem>): JSONObject {
    val local = knownTraceBox(raw, stock)
    val box = data.optJSONObject("box") ?: local?.data
    val candidate = data.optJSONObject("product")
    val product = if (box == null) candidate else {
        val id = box.nullable("product_id")
        products.firstOrNull { it.id == id }?.data ?: candidate?.takeIf { it.nullable("id") == id }
    }
    return JSONObject(data.toString()).put("box", box ?: JSONObject.NULL).put("product", product ?: JSONObject.NULL)
}
