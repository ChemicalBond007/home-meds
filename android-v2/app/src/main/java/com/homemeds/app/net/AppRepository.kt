package com.homemeds.app.net

import com.homemeds.app.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

internal fun snapshotMutation(path: String) = Regex("^/(products|stock|plans|doses|groups|members|warehouses)(/|$)").containsMatchIn(path)

internal class AppRepository(private val api: NativeApi) {
    private var legacy = false

    suspend fun bootstrap(group: String?): JSONObject {
        if (!legacy) try {
            return api.obj("GET", "/app/bootstrap", groupId = group).also { require(it.getInt("api_version") == 1) }
        } catch (e: ApiException) {
            if (e.status !in setOf(404, 405, 501, 413)) throw e
            if (e.status != 413) legacy = true
        }
        return legacyBootstrap(group)
    }

    suspend fun mutate(method: String, path: String, body: JSONObject?, group: String?): JSONObject =
        api.obj(method, path, body, group, state = if (legacy || !snapshotMutation(path)) null else "app")

    suspend fun sync(group: String): JSONObject {
        if (!legacy) try { return api.obj("GET", "/app/sync", groupId = group) }
        catch (e: ApiException) {
            if (e.status !in setOf(404, 405, 501)) throw e
            legacy = true
        }
        return coroutineScope {
            val plans = async { api.array("/plans", group) }
            val groups = async { api.array("/groups", group) }
            val zone = familyZone(groups.await().toString(), group)
            json("group_id" to group, "timezone" to zone.id, "plans" to plans.await())
        }
    }

    private suspend fun legacyBootstrap(requested: String?): JSONObject {
        val me = api.obj("GET", "/auth/me", groupId = requested)
        val result = JSONObject(me.toString()).put("api_version", 0).put("groups", JSONArray()).put("group_id", JSONObject.NULL)
        listOf("members", "products", "stock", "plans", "warehouses").forEach { result.put(it, JSONArray()) }
        result.put("today", JSONObject.NULL)
        if (me.optBoolean("binding_only")) return result
        val groups = api.array("/groups", requested)
        val user = User.parse(me.getJSONObject("user"))
        val group = if (user.admin) requested?.takeIf { id -> groups.objects().any { it.getString("id") == id } }
            ?: user.groupId?.takeIf { id -> groups.objects().any { it.getString("id") == id } } ?: groups.optJSONObject(0)?.getString("id")
            else user.groupId
        result.put("groups", groups).put("group_id", group ?: JSONObject.NULL)
        if (group == null) return result
        return coroutineScope {
            val members = async { api.array("/members", group) }
            val products = async { api.array("/products", group) }
            val stock = async { api.array("/stock", group) }
            val plans = async { api.array("/plans", group) }
            val warehouses = async { api.array("/warehouses", group) }
            val today = async { api.obj("GET", "/today", groupId = group) }
            result.put("members", members.await()).put("products", products.await()).put("stock", stock.await())
                .put("plans", plans.await()).put("warehouses", warehouses.await()).put("today", today.await())
        }
    }
}
