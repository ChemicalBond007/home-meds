package com.homemeds.app

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class CalendarSyncTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: CalendarProvider
    class CalendarProvider : ContentProvider() {
        val rows = linkedMapOf(1L to ("家药箱" to CalendarContract.ACCOUNT_TYPE_LOCAL),
            2L to ("赛博药箱" to CalendarContract.ACCOUNT_TYPE_LOCAL),
            3L to ("其他日历" to CalendarContract.ACCOUNT_TYPE_LOCAL),
            4L to ("家药箱" to "remote.account"))
        val deleted = mutableListOf<Uri>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(arrayOf(Calendars._ID)).apply {
                rows.filter { it.value.first == args?.get(0) && it.value.second == args.getOrNull(1) }
                    .keys.forEach { addRow(arrayOf(it)) }
            }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val id = uri.lastPathSegment!!.toLong()
            assertEquals(rows.getValue(id).first, uri.getQueryParameter(Calendars.ACCOUNT_NAME))
            assertEquals(CalendarContract.ACCOUNT_TYPE_LOCAL, uri.getQueryParameter(Calendars.ACCOUNT_TYPE))
            assertEquals("true", uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER))
            deleted += uri
            return if (rows.remove(id) != null) 1 else 0
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("unexpected insert")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri) = "vnd.android.cursor.dir/event"
    }
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        provider = CalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }
    @Test fun syncWithoutPlansStillCleansLegacyLocalAccountsOnly() {
        provider.rows.remove(2)
        assertEquals(0, CalendarSync.sync(app, emptyList(), ZoneId.of("Asia/Shanghai")))
        assertEquals(setOf(3L, 4L), provider.rows.keys)
        assertEquals(1, provider.deleted.size)
        assertEquals(0, CalendarSync.sync(app, emptyList(), ZoneId.of("Asia/Shanghai")))
        assertEquals(1, provider.deleted.size)
    }
    @Test fun logoutCleansBothNamesWithoutDeletingOtherCalendars() {
        CalendarSync.deleteAll(app)
        assertEquals(setOf(3L, 4L), provider.rows.keys)
        assertEquals(2, provider.deleted.size)
        CalendarSync.deleteAll(app)
        assertEquals(2, provider.deleted.size)
    }
    @Test fun deniedPermissionsDoNotTouchCalendars() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        CalendarSync.deleteAll(app)
        assertEquals(0, CalendarSync.sync(app, emptyList(), ZoneId.of("Asia/Shanghai")))
        assertEquals(4, provider.rows.size)
        assertTrue(provider.deleted.isEmpty())
    }
}
