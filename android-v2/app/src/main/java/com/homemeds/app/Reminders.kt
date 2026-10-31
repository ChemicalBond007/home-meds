package com.homemeds.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.LocalDate
import com.homemeds.app.net.ApiClient

object ReminderAlarms {
    const val CHANNEL = "dose_reminders"
    private const val VERIFY_TIMEOUT = 5L

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_description)
            enableVibration(true)
        }
        context.getSystemService<NotificationManager>()!!.createNotificationChannel(channel)
    }

    fun canNotify(context: Context) =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun canExact(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService<AlarmManager>()!!.canScheduleExactAlarms()

    private fun intent(context: Context, key: String) = Intent(context, ReminderReceiver::class.java)
        .setData(Uri.Builder().scheme("homemeds").authority("reminder").appendPath(key).build())

    fun schedule(context: Context, prefs: Prefs, alarms: List<Alarm>): Int {
        val am = context.getSystemService<AlarmManager>()!!
        val keys = alarms.map { it.key }.toSet()
        for (old in prefs.alarmKeys - keys) cancel(context, am, old)
        val exact = canExact(context)
        for (a in alarms) {
            val pi = PendingIntent.getBroadcast(
                context, 0, intent(context, a.key).putExtras(extras(a)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.at, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.at, pi)
            }
        }
        prefs.alarmKeys = keys
        return alarms.size
    }

    fun cancelAll(context: Context, prefs: Prefs) {
        val am = context.getSystemService<AlarmManager>()!!
        prefs.alarmKeys.forEach { cancel(context, am, it) }
        prefs.alarmKeys = emptySet()
    }

    private fun cancel(context: Context, am: AlarmManager, key: String) {
        PendingIntent.getBroadcast(context, 0, intent(context, key), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?.let { am.cancel(it); it.cancel() }
    }

    private fun extras(a: Alarm) = Bundle().apply {
        putString("key", a.key); putLong("at", a.at); putString("user_id", a.userId); putString("plan_id", a.planId)
        putString("group_id", a.groupId); putString("date", a.date); putString("time", a.time); putString("kind", a.kind)
        putString("medicine", a.medicine); putString("username", a.username)
    }

    fun alarm(b: Bundle): Alarm? {
        fun s(k: String) = b.getString(k)
        return Alarm(
            s("key") ?: return null, b.getLong("at"), s("user_id") ?: return null, s("plan_id") ?: return null,
            s("group_id") ?: return null, s("date") ?: return null, s("time") ?: return null, s("kind") ?: return null,
            s("medicine") ?: "", s("username") ?: "",
        )
    }

    /** Re-checks with the Worker so a dose someone already recorded does not ring again. */
    suspend fun deliver(context: Context, alarm: Alarm) {
        val prefs = Prefs(context)
        val session = prefs.session ?: return
        if (session.userId != alarm.userId || session.groupId != alarm.groupId || !prefs.appNotifications) return
        val resolved = try {
            resolved(alarm, prefs.members.toSet(), session.elderly)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (resolved == true) return
        notify(context, alarm, verified = resolved == false)
    }

    private suspend fun resolved(alarm: Alarm, members: Set<String>, elderly: Boolean): Boolean = coroutineScope {
        val plans = async { ApiClient.raw("GET", "/plans", groupId = alarm.groupId, timeout = VERIFY_TIMEOUT) }
        val doses = async { ApiClient.raw("GET", "/doses", groupId = alarm.groupId, timeout = VERIFY_TIMEOUT) }
        val plan = parsePlans(plans.await()).find { it.id == alarm.planId }
        if (plan == null || alarm.time !in plan.times || !Schedule.scheduledOn(plan, LocalDate.parse(alarm.date)) ||
            alarm.kind == "family" && (elderly || !plan.notifyFamily || plan.userId !in members)) {
            doses.cancel()
            return@coroutineScope true
        }
        val logs = JSONArray(doses.await())
        (0 until logs.length()).map { logs.getJSONObject(it) }.any {
            it.optString("plan_id") == alarm.planId && it.optString("local_date") == alarm.date &&
                it.optString("scheduled_time") == alarm.time && it.optString("status") != "undone"
        }
    }

    private fun notify(context: Context, alarm: Alarm, verified: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val self = alarm.kind == "self"
        val title = if (self) "该确认今天的药了" else "${alarm.username}还没有记录服药"
        val body = (if (self) "${alarm.time} ${alarm.medicine}，请打开应用核对时间、用量和药盒。"
        else "${alarm.time} 的服药尚未确认，请先联系家人核实，不要重复服药。") + if (verified) "" else "（暂时无法联网核对，可能已有人记录）"
        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.Builder().scheme("homemeds").authority("open").appendPath(alarm.key).build())
            .putExtra(MainActivity.EXTRA_PLAN_ID, alarm.planId)
            .putExtra(MainActivity.EXTRA_DATE, alarm.date)
            .putExtra(MainActivity.EXTRA_TIME, alarm.time)
            .putExtra(MainActivity.EXTRA_GROUP_ID, alarm.groupId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF164E44.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(alarm.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        NotificationManagerCompat.from(context).notify(alarm.key, 1, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarm = intent.extras?.let(ReminderAlarms::alarm) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderAlarms.deliver(app, alarm)
            } finally {
                SyncWorker.enqueue(app)
                pending.finish()
            }
        }
    }
}

/** Alarms do not survive reboots or clock changes; rebuild them from the last sync. */
class BootReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_TIME_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Sync.fromCache(app)
            } finally {
                SyncWorker.enqueue(app)
                pending.finish()
            }
        }
    }
}
