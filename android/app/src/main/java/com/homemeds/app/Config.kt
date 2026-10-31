package com.homemeds.app

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray

object Config {
    val baseUrl: String = BuildConfig.BASE_URL
    val origin: String = origin(baseUrl.toUri())

    fun origin(uri: Uri): String {
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}$port"
    }

    fun isSite(uri: Uri?) = uri != null && uri.scheme in setOf("http", "https") && origin(uri) == origin
}

fun String.hasChinese() = any { it in '\u4e00'..'\u9fff' }

class Prefs(context: Context) {
    data class Session(val userId: String, val groupId: String?, val elderly: Boolean)

    private val p = context.applicationContext.getSharedPreferences("home_meds", Context.MODE_PRIVATE)

    var session: Session?
        get() = p.getString("user_id", null)?.let { Session(it, p.getString("group_id", null), p.getBoolean("elderly", false)) }
        set(v) = p.edit {
            if (v == null) {
                remove("user_id"); remove("group_id"); remove("elderly")
            } else {
                putString("user_id", v.userId); putString("group_id", v.groupId); putBoolean("elderly", v.elderly)
            }
        }

    /** Family members whose plans go into the calendar; defaults to the signed-in user. */
    var members: List<String>
        get() = p.getString("members", null)?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }
            ?: listOfNotNull(session?.userId)
        set(v) = p.edit { putString("members", JSONArray(v).toString()) }

    var calendarAlerts: Boolean
        get() = p.getBoolean("calendar_alerts", true)
        set(v) = p.edit { putBoolean("calendar_alerts", v) }

    var appNotifications: Boolean
        get() = p.getBoolean("app_notifications", true)
        set(v) = p.edit { putBoolean("app_notifications", v) }

    var lastSync: Long?
        get() = p.getLong("last_sync", 0).takeIf { it > 0 }
        set(v) = p.edit { putLong("last_sync", v ?: 0) }

    var lastError: String?
        get() = p.getString("last_error", null)
        set(v) = p.edit { putString("last_error", v) }

    var events: Int
        get() = p.getInt("events", 0)
        set(v) = p.edit { putInt("events", v) }

    var alarms: Int
        get() = p.getInt("alarms", 0)
        set(v) = p.edit { putInt("alarms", v) }

    var alarmKeys: Set<String>
        get() = p.getStringSet("alarm_keys", null)?.toSet() ?: emptySet()
        set(v) = p.edit { putStringSet("alarm_keys", v) }

    /** Last plans and timezone, so alarms can be rebuilt after a reboot without network. */
    var cachedPlans: String?
        get() = p.getString("cached_plans", null)
        set(v) = p.edit { putString("cached_plans", v) }

    var cachedZone: String?
        get() = p.getString("cached_zone", null)
        set(v) = p.edit { putString("cached_zone", v) }

    fun clear() = p.edit { clear() }
}
