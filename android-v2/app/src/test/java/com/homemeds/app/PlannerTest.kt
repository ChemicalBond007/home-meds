package com.homemeds.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun plan(
    id: String = "p1",
    userId: String = "u1",
    username: String = "我",
    times: List<String> = listOf("08:00", "20:00"),
    frequency: String = "daily",
    weekdays: List<Int> = emptyList(),
    intervalDays: Int = 1,
    start: String = "2026-01-01",
    end: String? = null,
    enabled: Boolean = true,
    notifyFamily: Boolean = true,
) = Plan(
    id, "g1", userId, username, "阿司匹林", 1.5, "片", times, frequency, weekdays, intervalDays,
    LocalDate.parse(start), end?.let(LocalDate::parse), "after", "", enabled, notifyFamily,
)

class ScheduleTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun weeklyCountsSundayAsZeroLikeTheWorker() {
        val p = plan(frequency = "weekly", weekdays = listOf(0, 3))
        assertTrue(Schedule.scheduledOn(p, d("2026-01-04")))
        assertTrue(Schedule.scheduledOn(p, d("2026-01-07")))
        assertFalse(Schedule.scheduledOn(p, d("2026-01-05")))
    }

    @Test
    fun intervalCountsFromStartDate() {
        val p = plan(frequency = "interval", intervalDays = 3)
        assertTrue(Schedule.scheduledOn(p, d("2026-01-01")))
        assertTrue(Schedule.scheduledOn(p, d("2026-01-04")))
        assertFalse(Schedule.scheduledOn(p, d("2026-01-05")))
        assertFalse(Schedule.scheduledOn(p, d("2025-12-29")))
    }

    @Test
    fun endDateDisabledAndAsNeededAreNotScheduled() {
        assertTrue(Schedule.scheduledOn(plan(end = "2026-01-10"), d("2026-01-10")))
        assertFalse(Schedule.scheduledOn(plan(end = "2026-01-10"), d("2026-01-11")))
        assertFalse(Schedule.scheduledOn(plan(enabled = false), d("2026-01-05")))
        assertFalse(Schedule.scheduledOn(plan(frequency = "as_needed"), d("2026-01-05")))
    }

    @Test
    fun recurrenceRules() {
        assertEquals("FREQ=DAILY", Schedule.rrule(plan(), shanghai))
        assertEquals("FREQ=DAILY;INTERVAL=2", Schedule.rrule(plan(frequency = "interval", intervalDays = 2), shanghai))
        assertEquals(
            "FREQ=WEEKLY;BYDAY=SU,WE;UNTIL=20260201T155959Z",
            Schedule.rrule(plan(frequency = "weekly", weekdays = listOf(3, 0), end = "2026-02-01"), shanghai),
        )
        assertNull(Schedule.rrule(plan(frequency = "weekly"), shanghai))
        assertNull(Schedule.rrule(plan(frequency = "as_needed"), shanghai))
        assertNull(Schedule.rrule(plan(enabled = false), shanghai))
    }

    @Test
    fun firstWeeklyDateMatchesWeekday() {
        assertEquals(d("2026-01-07"), Schedule.firstDate(plan(frequency = "weekly", weekdays = listOf(3))))
        assertEquals(d("2026-01-01"), Schedule.firstDate(plan()))
    }

    @Test
    fun occurrencesUseFamilyLocalTimeAcrossDaylightSaving() {
        val ny = ZoneId.of("America/New_York")
        val p = plan(times = listOf("08:00"), start = "2026-03-01")
        val all = Schedule.occurrences(listOf(p), ny, Instant.parse("2026-03-07T00:00:00Z"), Instant.parse("2026-03-10T00:00:00Z"))
        assertEquals(
            listOf("2026-03-07T13:00:00Z", "2026-03-08T12:00:00Z", "2026-03-09T12:00:00Z"),
            all.map { it.at.toString() },
        )
    }
}

class CalendarPlannerTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun eventsForSelectedMembersOnly() {
        val plans = listOf(plan(), plan(id = "p2", userId = "u2", username = "奶奶", times = listOf("09:30")), plan(id = "p3", userId = "u3"))
        val events = CalendarPlanner.events(plans, setOf("u1", "u2"), "u1", zone, alert = true)
        assertEquals(listOf("p1|08:00", "p1|20:00", "p2|09:30"), events.map { it.key })
        assertEquals("吃药：阿司匹林（1.5片）", events[0].title)
        assertEquals("奶奶吃药：阿司匹林（1.5片）", events[2].title)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), events[0].start)
        assertTrue(events[0].description.contains("饭后"))
        assertTrue(events[0].description.contains("不代表已经服药"))
        assertTrue(events[0].description.contains("赛博药箱"))
        assertFalse(events[0].description.contains("家药箱"))
    }

    @Test
    fun fingerprintChangesWithAlertSetting() {
        val on = CalendarPlanner.events(listOf(plan()), setOf("u1"), "u1", zone, alert = true)[0]
        val off = CalendarPlanner.events(listOf(plan()), setOf("u1"), "u1", zone, alert = false)[0]
        assertNotEquals(on.fingerprint, off.fingerprint)
        assertEquals(on.fingerprint, on.copy().fingerprint)
    }

    @Test
    fun asNeededAndDisabledPlansHaveNoEvents() {
        val events = CalendarPlanner.events(listOf(plan(frequency = "as_needed"), plan(id = "p2", enabled = false)), setOf("u1"), "u1", zone, true)
        assertTrue(events.isEmpty())
    }
}

class ReminderPlannerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-01-09T23:00:00Z") // 2026-01-10 07:00 in Shanghai

    @Test
    fun remindsSelfAtDoseTimeForTwoDays() {
        val alarms = ReminderPlanner.alarms(listOf(plan()), "u1", false, setOf("u1"), zone, now)
        assertEquals(listOf("p1|2026-01-10|08:00|self", "p1|2026-01-10|20:00|self", "p1|2026-01-11|08:00|self", "p1|2026-01-11|20:00|self"), alarms.map { it.key })
        assertEquals(Instant.parse("2026-01-10T00:00:00Z").toEpochMilli(), alarms[0].at)
    }

    @Test
    fun remindsSelectedFamilyThirtyMinutesLate() {
        val grandma = plan(id = "p2", userId = "u2", username = "奶奶", times = listOf("06:50"))
        val alarms = ReminderPlanner.alarms(listOf(grandma), "u1", false, setOf("u1", "u2"), zone, now)
        assertEquals("p2|2026-01-10|06:50|family", alarms[0].key)
        assertEquals(Instant.parse("2026-01-09T23:20:00Z").toEpochMilli(), alarms[0].at)
        assertEquals("奶奶", alarms[0].username)
    }

    @Test
    fun skipsFamilyWhenElderlyUnselectedOrNotRequested() {
        val grandma = plan(id = "p2", userId = "u2", times = listOf("09:00"))
        assertTrue(ReminderPlanner.alarms(listOf(grandma), "u1", true, setOf("u2"), zone, now).isEmpty())
        assertTrue(ReminderPlanner.alarms(listOf(grandma), "u1", false, setOf("u1"), zone, now).isEmpty())
        assertTrue(ReminderPlanner.alarms(listOf(grandma.copy(notifyFamily = false)), "u1", false, setOf("u2"), zone, now).isEmpty())
    }

    @Test
    fun notificationOpensConfirmationUrl() {
        val alarm = ReminderPlanner.alarms(listOf(plan()), "u1", false, setOf("u1"), zone, now)[0]
        assertEquals("/?plan=p1&date=2026-01-10&time=08%3A00&group=g1", ReminderPlanner.path(alarm))
    }
}
