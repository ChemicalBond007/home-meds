package com.homemeds.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homemeds.app.*
import com.homemeds.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class AppState(
    val loading: Boolean = true, val busy: Boolean = false, val user: User? = null,
    val bindingOnly: Boolean = false, val groups: List<FamilyGroup> = emptyList(), val groupId: String? = null,
    val members: List<User> = emptyList(), val products: List<Product> = emptyList(), val stock: List<StockItem> = emptyList(),
    val plans: List<Plan> = emptyList(), val todos: List<Todo> = emptyList(), val warehouses: List<JSONObject> = emptyList(),
    val today: String = LocalDate.now(DEFAULT_ZONE).toString(), val timezone: String = "Asia/Shanghai",
    val error: String? = null, val notice: String? = null,
    val assistantTarget: String? = null, val assistantPhone: Boolean = true,
)

internal fun assistantTarget(state: AppState): String? {
    val target = state.assistantTarget ?: state.user?.id
    return target?.takeIf { id -> state.members.any { it.id == id } }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val app get() = getApplication<Application>()
    val prefs = Prefs(app)
    private val mutable = MutableStateFlow(AppState(assistantPhone = prefs.assistantPhone))
    val state = mutable.asStateFlow()
    private var generation = 0
    private var refreshJob: Job? = null
    private var syncJob: Job? = null
    private var operation: Job? = null

    init {
        viewModelScope.launch { restore() }
        viewModelScope.launch {
            while (isActive) {
                delay(60000)
                if (mutable.value.user != null && !mutable.value.bindingOnly && !mutable.value.busy) refresh()
            }
        }
    }
    fun error(message: String?) { mutable.update { it.copy(error = message) } }
    fun notice(message: String?) { mutable.update { it.copy(notice = message) } }
    private suspend fun restore() {
        try {
            val me = ApiClient.request("GET", "/auth/me")
            val user = User.parse(me.getJSONObject("user"))
            mutable.update { it.copy(user = user, bindingOnly = me.optBoolean("binding_only"), loading = false) }
            if (!me.optBoolean("binding_only")) loadGroups(user)
        } catch (e: CancellationException) { throw e }
        catch (e: ApiException) {
            if (e.status == 401) { Sync.logout(app); mutable.value = AppState(loading = false) }
            else mutable.update { it.copy(loading = false, error = e.message) }
        } catch (_: Exception) { mutable.update { it.copy(loading = false, error = "无法连接服务器，请检查网络后重试") } }
    }
    private suspend fun loadGroups(user: User) {
        val groups = ApiClient.array("/groups").objects().map(FamilyGroup::parse)
        val selected = if (user.admin) (prefs.activeGroupId?.takeIf { id -> groups.any { it.id == id } } ?: groups.firstOrNull()?.id)
            else user.groupId
        mutable.update { it.copy(groups = groups) }
        selectGroupNow(selected)
    }
    private suspend fun selectGroupNow(id: String?) {
        val user = mutable.value.user ?: return
        generation++
        if (refreshJob != currentCoroutineContext()[Job]) refreshJob?.cancel()
        mutable.update { it.copy(groupId = id, members = emptyList(), products = emptyList(), stock = emptyList(),
            plans = emptyList(), todos = emptyList(), warehouses = emptyList(),
            assistantTarget = id?.let { prefs.assistantTarget(user.id, it) }, assistantPhone = prefs.assistantPhone) }
        Sync.sessionChanged(app, Prefs.Session(user.id, id, user.elderly))
        if (id != null) refreshNow()
        syncDebounced()
    }
    fun selectGroup(id: String) {
        if (id == mutable.value.groupId || mutable.value.busy) return
        perform { selectGroupNow(id) }
    }
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                if (mutable.value.user == null) restore() else {
                    val me = ApiClient.request("GET", "/auth/me")
                    val user = User.parse(me.getJSONObject("user"))
                    val previous = mutable.value.user
                    mutable.update { it.copy(user = user) }
                    if (previous?.groupId != user.groupId || previous?.elderly != user.elderly) loadGroups(user) else refreshNow()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handle(e) }
        }
    }
    private suspend fun refreshNow() {
        val g = mutable.value.groupId ?: return
        val epoch = generation
        coroutineScope {
            val members = async { ApiClient.array("/members", g).objects().map(User::parse) }
            val products = async { ApiClient.array("/products", g).objects().map(::Product) }
            val stock = async { ApiClient.array("/stock", g).objects().map(::StockItem) }
            val plans = async { parsePlans(ApiClient.raw("GET", "/plans", groupId = g)) }
            val today = async { ApiClient.request("GET", "/today", groupId = g) }
            val warehouses = async { ApiClient.array("/warehouses", g).objects() }
            val next = mutable.value.copy(members = members.await(), products = products.await(), stock = stock.await(),
                plans = plans.await(), warehouses = warehouses.await(), todos = today.await().getJSONArray("items").objects().map(Todo::parse),
                today = today.await().getString("date"), timezone = today.await().getString("timezone"))
            if (generation == epoch) mutable.update { next.copy(busy = it.busy, error = it.error, notice = it.notice,
                assistantTarget = it.assistantTarget, assistantPhone = it.assistantPhone) }
        }
    }
    private suspend fun handle(e: Exception) {
        if (e is ApiException && e.status == 401) {
            generation++; Sync.logout(app)
            mutable.value = AppState(loading = false, error = "登录已失效，请重新登录")
        } else error(e.message?.takeIf { it.hasChinese() } ?: "操作失败，请检查网络或稍后重试")
    }
    fun perform(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            refreshJob?.cancel()
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { handle(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun authenticate(path: String, body: JSONObject) = perform {
        ApiClient.request("POST", path, body)
        restore()
    }
    fun bound() = perform { restore() }
    suspend fun get(path: String): JSONObject = ApiClient.request("GET", path, groupId = mutable.value.groupId)
    suspend fun list(path: String): JSONArray = ApiClient.array(path, mutable.value.groupId)
    suspend fun post(path: String, body: JSONObject = JSONObject()) = ApiClient.request("POST", path, body, mutable.value.groupId)

    fun save(method: String, path: String, body: JSONObject? = null, done: (JSONObject) -> Unit = {}) = perform {
        done(mutate(method, path, body))
    }
    suspend fun mutate(method: String, path: String, body: JSONObject? = null): JSONObject {
        val response = ApiClient.request(method, path, body, mutable.value.groupId)
        if (path.startsWith("/plans") || path.startsWith("/doses") || path.startsWith("/members") || path.startsWith("/groups")) syncDebounced()
        try {
            if (path.startsWith("/groups")) {
                val groups = ApiClient.array("/groups").objects().map(FamilyGroup::parse)
                mutable.update { it.copy(groups = groups) }
                if (mutable.value.groupId == null) selectGroupNow(groups.firstOrNull()?.id)
            }
            if (mutable.value.groupId != null) refreshNow()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            notice("已保存，但列表刷新失败，请手动刷新，不要重复提交。")
        }
        return response
    }
    fun syncDebounced() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch { delay(1500); Sync.syncNow(app) }
    }
    fun setAssistantTarget(id: String) {
        val s = mutable.value
        val user = s.user ?: return
        val group = s.groupId ?: return
        if (s.members.none { it.id == id }) { error("请选择当前家庭成员"); return }
        prefs.setAssistantTarget(user.id, group, id)
        mutable.update { it.copy(assistantTarget = id) }
    }
    fun setAssistantPhone(phone: Boolean) {
        prefs.assistantPhone = phone
        mutable.update { it.copy(assistantPhone = phone) }
    }
    fun syncNow() = perform { Sync.syncNow(app); notice(prefs.lastError ?: "已同步 ${prefs.events} 个日历日程、${prefs.alarms} 个提醒") }
    fun logout() = perform {
        try { post("/auth/logout") } finally {
            generation++; refreshJob?.cancel(); syncJob?.cancel()
            Sync.logout(app)
            mutable.value = AppState(loading = false)
        }
    }
}
