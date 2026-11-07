package com.homemeds.app

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.homemeds.app.net.ApiClient
import com.homemeds.app.net.ApiException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Pulls plans from the Worker and rebuilds calendar events and reminder alarms. */
object Sync {
    enum class Outcome { OK, RETRY, SKIPPED }

    private val mutex = Mutex()

    suspend fun run(context: Context): Outcome = withContext(Dispatchers.IO) { mutex.withLock { runLocked(context.applicationContext) } }

    private suspend fun runLocked(context: Context): Outcome {
        val prefs = Prefs(context)
        val session = prefs.session ?: return Outcome.SKIPPED
        val groupId = session.groupId ?: run {
            prefs.lastError = "请先加入家庭"
            return Outcome.SKIPPED
        }
        return try {
            val snapshot = ApiClient.repository.sync(groupId)
            if (snapshot.getString("group_id") != groupId || snapshot.optJSONObject("user")?.getString("id")?.let { it != session.userId } == true)
                throw ApiException(403, "账号或家庭已变更，请重新打开应用")
            val plansBody = snapshot.getJSONArray("plans").toString()
            val zone = ZoneId.of(snapshot.getString("timezone"))
            prefs.cachedPlans = plansBody
            prefs.cachedZone = zone.id
            apply(context, prefs, session, parsePlans(plansBody), zone)
            prefs.lastSync = System.currentTimeMillis()
            prefs.lastError = null
            Outcome.OK
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) clearLocal(context, prefs)
            if (e.status == 401) { prefs.session = null; prefs.activeGroupId = null }
            prefs.lastError = e.message
            if (e.status == 401 || e.status == 403) Outcome.SKIPPED else Outcome.RETRY
        } catch (e: IOException) {
            prefs.lastError = "网络不可用，已按上次同步的计划安排提醒"
            applyCached(context, prefs, session)
            Outcome.RETRY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.lastError = e.message?.takeIf { it.hasChinese() } ?: "同步失败，请稍后重试"
            Outcome.RETRY
        }
    }

    suspend fun fromCache(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = Prefs(context)
            prefs.session?.let { applyCached(context, prefs, it) }
        }
    }

    private fun applyCached(context: Context, prefs: Prefs, session: Prefs.Session) {
        val plans = prefs.cachedPlans ?: return
        val zone = prefs.cachedZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: DEFAULT_ZONE
        runCatching { apply(context, prefs, session, parsePlans(plans), zone) }
    }

    private fun apply(context: Context, prefs: Prefs, session: Prefs.Session, plans: List<Plan>, zone: ZoneId) {
        val members = prefs.members.toSet()
        prefs.events = if (CalendarSync.hasPermission(context)) {
            try {
                CalendarSync.sync(context, CalendarPlanner.events(plans, members, session.userId, zone, prefs.calendarAlerts), zone)
            } catch (_: SecurityException) {
                0
            }
        } else 0
        prefs.alarms = if (prefs.appNotifications) {
            ReminderAlarms.schedule(context, prefs, ReminderPlanner.alarms(plans, session.userId, session.elderly, members, zone, Instant.now()))
        } else {
            ReminderAlarms.cancelAll(context, prefs)
            0
        }
    }

    suspend fun sessionChanged(context: Context, next: Prefs.Session, enqueue: Boolean = true) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val prefs = Prefs(app)
                val previous = prefs.session
                if (previous != null && (previous.userId != next.userId || previous.groupId != next.groupId)) clearLocal(app, prefs)
                if (previous?.userId != next.userId) prefs.members = listOf(next.userId)
                if (previous?.groupId != next.groupId) { prefs.cachedPlans = null; prefs.cachedZone = null }
                prefs.session = next
                prefs.activeGroupId = next.groupId
            }
        }
        if (enqueue) SyncWorker.enqueue(app)
    }

    suspend fun acceptSnapshot(context: Context, userId: String, groupId: String, plans: String, timezone: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = Prefs(context)
            val session = prefs.session ?: return@withLock
            if (session.userId != userId || session.groupId != groupId) return@withLock
            if (prefs.cachedPlans == plans && prefs.cachedZone == timezone &&
                System.currentTimeMillis() - (prefs.lastSync ?: 0) < TimeUnit.HOURS.toMillis(1)) return@withLock
            val zone = ZoneId.of(timezone)
            prefs.cachedPlans = plans; prefs.cachedZone = timezone
            apply(context, prefs, session, parsePlans(plans), zone)
            prefs.lastSync = System.currentTimeMillis(); prefs.lastError = null
        }
    }

    suspend fun reapply(context: Context) {
        if (Prefs(context).cachedPlans != null) fromCache(context) else syncNow(context)
    }

    suspend fun syncNow(context: Context) = run(context)

    suspend fun logout(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = Prefs(context)
            clearLocal(context, prefs)
            prefs.clear()
        }
    }

    private fun clearLocal(context: Context, prefs: Prefs) {
        ReminderAlarms.cancelAll(context, prefs)
        runCatching { CalendarSync.deleteAll(context) }
        NotificationManagerCompat.from(context).cancelAll()
        prefs.cachedPlans = null
        prefs.cachedZone = null
        prefs.events = 0
        prefs.alarms = 0
        prefs.lastSync = null
        prefs.lastError = null
    }

    fun configure(context: Context, params: JSONObject) {
        val prefs = Prefs(context)
        params.optJSONArray("members")?.let { a ->
            prefs.members = (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() && it.length <= 64 }.distinct().take(100)
        }
        if (params.has("calendar_alerts")) prefs.calendarAlerts = params.getBoolean("calendar_alerts")
        if (params.has("app_notifications")) prefs.appNotifications = params.getBoolean("app_notifications")
    }

    fun status(context: Context): JSONObject {
        val prefs = Prefs(context)
        val power = context.getSystemService<PowerManager>()
        return JSONObject()
            .put("calendar_permission", CalendarSync.hasPermission(context))
            .put("notification_permission", ReminderAlarms.canNotify(context))
            .put("exact_alarm", ReminderAlarms.canExact(context))
            .put("battery_optimized", power?.isIgnoringBatteryOptimizations(context.packageName) == false)
            .put("members", JSONArray(prefs.members))
            .put("calendar_alerts", prefs.calendarAlerts)
            .put("app_notifications", prefs.appNotifications)
            .put("last_sync", prefs.lastSync ?: JSONObject.NULL)
            .put("last_error", prefs.lastError ?: JSONObject.NULL)
            .put("events", prefs.events)
            .put("alarms", prefs.alarms)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (Sync.run(applicationContext)) {
        Sync.Outcome.RETRY -> if (runAttemptCount < 3) Result.retry() else Result.success()
        else -> Result.success()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Alarms cover 48 hours, so a sync every 6 hours keeps the window filled. */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build(),
            )
        }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build(),
            )
        }
    }
}
