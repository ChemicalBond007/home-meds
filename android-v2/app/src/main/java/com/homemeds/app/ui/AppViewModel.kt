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

internal fun appSnapshot(body: JSONObject, previous: AppState): AppState {
    require(body.getInt("api_version") in 0..1) { "状态接口版本不受支持" }
    val user = User.parse(body.getJSONObject("user"))
    val groups = body.getJSONArray("groups").objects().map(FamilyGroup::parse)
    val group = body.nullable("group_id")
    val limited = body.getBoolean("binding_only")
    require(limited || user.admin || group == user.groupId) { "账号所属家庭已变更" }
    require(group == null || groups.any { it.id == group }) { "家庭状态不完整" }
    val today = body.optJSONObject("today")
    require(limited || group == null || today != null) { "今日清单状态不完整" }
    return previous.copy(loading = false, user = user, bindingOnly = limited, groups = groups, groupId = group,
        members = body.getJSONArray("members").objects().map(User::parse),
        products = body.getJSONArray("products").objects().map(::Product),
        stock = body.getJSONArray("stock").objects().map(::StockItem),
        plans = parsePlans(body.getJSONArray("plans").toString()),
        warehouses = body.getJSONArray("warehouses").objects(),
        todos = today?.getJSONArray("items")?.objects()?.map(Todo::parse).orEmpty(),
        today = today?.getString("date") ?: LocalDate.now(DEFAULT_ZONE).toString(),
        timezone = today?.getString("timezone") ?: "Asia/Shanghai")
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
        val epoch = generation
        try {
            acceptSnapshot(ApiClient.repository.bootstrap(prefs.activeGroupId), epoch)
        } catch (e: CancellationException) { throw e }
        catch (e: ApiException) {
            if (epoch != generation) return
            if (e.status == 401) { Sync.logout(app); mutable.value = AppState(loading = false) }
            else mutable.update { it.copy(loading = false, error = e.message) }
        } catch (_: Exception) {
            if (epoch == generation) mutable.update { it.copy(loading = false, error = "无法连接服务器，请检查网络后重试") }
        }
    }
    private suspend fun acceptSnapshot(body: JSONObject, epoch: Int) {
        if (epoch != generation) return
        val before = mutable.value
        val next = appSnapshot(body, before)
        val user = next.user ?: return
        val contextChanged = before.user?.id != user.id || before.groupId != next.groupId
        val session = Prefs.Session(user.id, next.groupId, user.elderly)
        if (prefs.session != session || prefs.activeGroupId != next.groupId) Sync.sessionChanged(app, session, enqueue = false)
        if (epoch != generation) return
        if (!next.bindingOnly && next.groupId != null) {
            try { Sync.acceptSnapshot(app, user.id, next.groupId, body.getJSONArray("plans").toString(), next.timezone) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice("数据已更新，本地提醒同步失败，请到设置重试。") }
        }
        if (epoch != generation) return
        mutable.update { next.copy(busy = it.busy, error = it.error, notice = it.notice,
            assistantTarget = if (contextChanged) next.groupId?.let { id -> prefs.assistantTarget(user.id, id) } else it.assistantTarget,
            assistantPhone = prefs.assistantPhone) }
    }
    private suspend fun selectGroupNow(id: String?) {
        val user = mutable.value.user ?: return
        generation++
        if (refreshJob != currentCoroutineContext()[Job]) refreshJob?.cancel()
        mutable.update { it.copy(groupId = id, members = emptyList(), products = emptyList(), stock = emptyList(),
            plans = emptyList(), todos = emptyList(), warehouses = emptyList(),
            assistantTarget = id?.let { prefs.assistantTarget(user.id, it) }, assistantPhone = prefs.assistantPhone) }
        val epoch = generation
        acceptSnapshot(ApiClient.repository.bootstrap(id), epoch)
    }
    fun selectGroup(id: String) {
        if (id == mutable.value.groupId || mutable.value.busy) return
        perform { selectGroupNow(id) }
    }
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                if (mutable.value.user == null) restore() else refreshNow()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handle(e) }
        }
    }
    private suspend fun refreshNow() {
        val epoch = generation
        acceptSnapshot(ApiClient.repository.bootstrap(mutable.value.groupId), epoch)
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
            generation++
            refreshJob?.cancel()
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { handle(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun authenticate(path: String, body: JSONObject) = perform {
        generation++
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
        val epoch = generation
        val response = ApiClient.repository.mutate(method, path, body, mutable.value.groupId)
        if (epoch != generation || !snapshotMutation(path)) return response
        try {
            val snapshot = response.optJSONObject("app_state")
            if (snapshot != null) acceptSnapshot(snapshot, epoch) else refreshNow()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            notice("已保存，但列表刷新失败，请手动刷新，不要重复提交。")
        }
        return response
    }
    fun syncDebounced() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch { delay(1500); Sync.reapply(app) }
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
