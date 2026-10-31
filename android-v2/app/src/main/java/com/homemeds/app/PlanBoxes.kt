package com.homemeds.app

import org.json.JSONObject

data class PlanBoxes(val boxes: List<StockItem>, val preferred: String?, val last: String?) {
    companion object {
        fun parse(o: JSONObject) = PlanBoxes(o.getJSONArray("boxes").objects().map(::StockItem),
            o.nullable("preferred_stock_item_id"), o.nullable("last_stock_item_id"))
    }
}

fun availableBoxes(stock: List<StockItem>, productId: String?) = stock.filter { it.productId == productId && it.available }
    .sortedWith(compareBy<StockItem> { it.expiry ?: "9999-99-99" }.thenBy { it.id })
fun expiryLabel(box: StockItem, today: String) = box.expiry?.let { "有效期至 $it" + if (it < today) "（已过期）" else "" } ?: "有效期未填写，请核对实物"
fun boxLabel(box: StockItem, today: String) = "${expiryLabel(box, today)} · ${box.warehouse} · 批号 ${box.data.nullable("batch_number") ?: "未填写"} · " +
    "${if (box.data.optInt("opened") == 1) "已开封" else "未开封"} · 剩余 ${Texts.amount(box.remaining)} ${box.unit} · 药盒 ${box.trace ?: box.id.takeLast(8)}"
fun initialDoseBox(data: PlanBoxes): String? = (data.preferred ?: data.last)?.takeIf { id -> data.boxes.any { it.id == id } }
fun canConfirmDoseBox(data: PlanBoxes?, selected: String?, verified: Boolean) =
    data != null && verified && (selected == "" || data.boxes.any { it.id == selected })
