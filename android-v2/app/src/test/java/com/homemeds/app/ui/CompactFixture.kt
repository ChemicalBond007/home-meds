package com.homemeds.app.ui

import com.homemeds.app.json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/** Canned API responses for a small admin family, used to render real screens at phone size. */
internal object CompactFixture {
    const val TODAY = "2026-11-08"
    fun user(id: String, name: String, admin: Boolean = false, elderly: Boolean = false) = json("id" to id, "username" to name,
        "role" to if (admin) "admin" else "user", "group_id" to "g", "is_elderly" to if (elderly) 1 else 0)
    fun plan(id: String, user: String, name: String, product: String?, times: List<String>, frequency: String = "daily",
        meal: String = "after", enabled: Int = 1) = json("id" to id, "group_id" to "g", "user_id" to user, "username" to "",
        "medicine_name" to name, "amount" to 1, "unit" to "片", "times" to JSONArray(times), "frequency" to frequency,
        "weekdays" to JSONArray(), "interval_days" to 1, "start_date" to "2026-10-01", "end_date" to null,
        "meal_relation" to meal, "notes" to "", "enabled" to enabled, "notify_family" to 0, "product_id" to product)
    fun box(id: String, product: String, name: String, expiry: String, remaining: Int = 20) = json("id" to id, "product_id" to product,
        "name" to name, "base_unit" to "片", "units_per_package" to 30, "units_remaining" to remaining, "status" to "active",
        "expiry_date" to expiry, "warehouse_name" to "客厅药箱", "warehouse_id" to "w1", "batch_number" to "B2405", "metadata_version" to 1)
    private val aspirin = plan("pl1", "a", "阿司匹林肠溶片", "p1", listOf("08:00", "20:00"))
    private val metformin = plan("pl2", "m", "二甲双胍缓释片", "p2", listOf("12:00"), meal = "with")
    private val ibuprofen = plan("pl3", "a", "布洛芬缓释胶囊", null, emptyList(), frequency = "as_needed", meal = "none")
    val members = JSONArray().put(user("a", "管理员", admin = true)).put(user("m", "奶奶", elderly = true))
    val stock = JSONArray().put(box("b1", "p1", "阿司匹林肠溶片", "2027-05-31")).put(box("b2", "p2", "二甲双胍缓释片", "2026-11-20"))
        .put(box("b3", "p1", "阿司匹林肠溶片", "2026-10-01", 6))
    fun snapshot(admin: Boolean = true) = json("api_version" to 1, "binding_only" to false,
        "user" to if (admin) user("a", "管理员", admin = true) else user("m", "奶奶"),
        "groups" to JSONArray().put(json("id" to "g", "name" to "张家", "timezone" to "Asia/Shanghai", "join_code" to "ABCD-1234",
            "meal_times" to json("morning" to "08:00", "noon" to "12:00", "evening" to "18:00", "bedtime" to "21:30"))),
        "group_id" to "g", "members" to members,
        "products" to JSONArray().put(json("id" to "p1", "name" to "阿司匹林肠溶片", "base_unit" to "片", "units_per_package" to 30,
            "specification" to "100mg×30片", "manufacturer" to "拜耳医药", "status" to "confirmed", "indications" to "抄录自说明书"))
            .put(json("id" to "p2", "name" to "二甲双胍缓释片", "base_unit" to "片", "units_per_package" to 60, "status" to "pending")),
        "stock" to stock, "plans" to JSONArray().put(aspirin).put(metformin).put(ibuprofen),
        "warehouses" to JSONArray().put(json("id" to "w1", "name" to "客厅药箱")).put(json("id" to "w2", "name" to "卧室药箱")),
        "today" to json("date" to TODAY, "timezone" to "Asia/Shanghai", "items" to JSONArray()
            .put(json("plan" to aspirin, "local_date" to TODAY, "scheduled_time" to "08:00", "state" to "taken",
                "log" to json("id" to "d1", "actor_name" to "管理员")))
            .put(json("plan" to aspirin, "local_date" to TODAY, "scheduled_time" to "20:00", "state" to "pending"))
            .put(json("plan" to metformin, "local_date" to TODAY, "scheduled_time" to "12:00", "state" to "pending"))))

    fun dispatcher(admin: Boolean = true) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            val body: Any = when {
                path.endsWith("/app/bootstrap") -> snapshot(admin)
                path.endsWith("/admin/users") -> members
                path.endsWith("/admin/logs") -> json("items" to JSONArray().put(json("action" to "dose.create", "outcome" to "success",
                    "created_at" to "2026-11-08 08:02", "actor_name" to "管理员", "group_name" to "张家", "subject_name" to "阿司匹林肠溶片")), "next_cursor" to null)
                path.endsWith("/ai/settings") -> json("settings" to json("provider" to "custom", "model" to "model-x",
                    "base_url" to "https://llm.example.com", "search_mode" to "external", "native_search_api" to "responses",
                    "extra_body" to JSONObject(), "native_search_body" to JSONObject()), "has_llm_key" to true, "has_tavily_key" to false)
                path.endsWith("/auth/recovery") -> json("totp" to true, "remaining" to 8, "passkeys" to 1)
                path.endsWith("/movements") -> JSONArray().put(json("name" to "阿司匹林肠溶片", "type" to "dose", "amount" to 1,
                    "username" to "管理员", "created_at" to "2026-11-08 08:02"))
                path.endsWith("/boxes") -> json("boxes" to JSONArray().put(stock.getJSONObject(0)), "preferred_stock_item_id" to "b1",
                    "last_stock_item_id" to null)
                else -> JSONObject()
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
        }
    }
}
