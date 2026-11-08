package com.homemeds.app

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.core.content.ContextCompat
import java.time.ZoneId

/**
 * Keeps a local, phone-only calendar named 赛博药箱 in step with the plans. Events
 * are matched by SYNC_DATA1 (plan|time) and replaced when their fingerprint changes.
 */
object CalendarSync {
    private val isTestApp = BuildConfig.APPLICATION_ID.endsWith(".test")
    private val ACCOUNT = if (isTestApp) "赛博药箱测试" else "赛博药箱"
    private const val LEGACY_ACCOUNT = "家药箱"

    fun hasPermission(context: Context) = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private fun Uri.asSyncAdapter(account: String = ACCOUNT): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private fun find(cr: ContentResolver): Long? = cr.query(
        Calendars.CONTENT_URI, arrayOf(Calendars._ID),
        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun create(cr: ContentResolver, zone: ZoneId): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "home_meds")
            put(Calendars.CALENDAR_DISPLAY_NAME, ACCOUNT)
            put(Calendars.CALENDAR_COLOR, 0xFF164E44.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, zone.id)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
        }
        val uri = cr.insert(Calendars.CONTENT_URI.asSyncAdapter(), values) ?: throw UserError("无法创建“赛博药箱”日历")
        return ContentUris.parseId(uri)
    }

    /** Returns the number of recurring events now in the calendar. */
    fun sync(context: Context, specs: List<EventSpec>, zone: ZoneId): Int {
        if (!hasPermission(context)) return 0
        val cr = context.contentResolver
        if (!isTestApp) deleteAccount(cr, LEGACY_ACCOUNT)
        val calendar = find(cr) ?: if (specs.isEmpty()) return 0 else create(cr, zone)
        val desired = specs.associateBy { it.key }
        val kept = mutableSetOf<String>()
        cr.query(
            Events.CONTENT_URI, arrayOf(Events._ID, Events.SYNC_DATA1, Events.SYNC_DATA2),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(1)
                if (key != null && key !in kept && desired[key]?.fingerprint == c.getString(2)) kept += key
                else cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, c.getLong(0)).asSyncAdapter(), null, null)
            }
        }
        for (spec in specs) if (spec.key !in kept) insert(cr, calendar, spec)
        return specs.size
    }

    private fun insert(cr: ContentResolver, calendar: Long, spec: EventSpec) {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar)
            put(Events.TITLE, spec.title)
            put(Events.DESCRIPTION, spec.description)
            put(Events.DTSTART, spec.start)
            put(Events.DURATION, "PT15M")
            put(Events.RRULE, spec.rrule)
            put(Events.EVENT_TIMEZONE, spec.timezone)
            put(Events.HAS_ALARM, if (spec.alert) 1 else 0)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
            put(Events.SYNC_DATA1, spec.key)
            put(Events.SYNC_DATA2, spec.fingerprint)
        }
        val uri = cr.insert(Events.CONTENT_URI.asSyncAdapter(), values) ?: return
        if (spec.alert) cr.insert(Reminders.CONTENT_URI.asSyncAdapter(), ContentValues().apply {
            put(Reminders.EVENT_ID, ContentUris.parseId(uri))
            put(Reminders.MINUTES, 0)
            put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })
    }

    fun deleteAll(context: Context) {
        if (!hasPermission(context)) return
        val cr = context.contentResolver
        deleteAccount(cr, ACCOUNT)
        if (!isTestApp) deleteAccount(cr, LEGACY_ACCOUNT)
    }

    private fun deleteAccount(cr: ContentResolver, account: String) {
        val ids = cr.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
            arrayOf(account, CalendarContract.ACCOUNT_TYPE_LOCAL), null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }.orEmpty()
        ids.forEach { cr.delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, it).asSyncAdapter(account), null, null) }
    }
}
