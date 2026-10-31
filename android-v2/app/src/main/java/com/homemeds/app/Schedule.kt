package com.homemeds.app

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Mirrors `src/shared/schedule.ts`; the Worker remains the source of truth for dose state. */
object Schedule {
    fun todayTodos(plans: List<Plan>, logs: List<DoseLog>, zone: ZoneId, now: Instant = Instant.now()): List<Todo> {
        val clock = now.atZone(zone)
        val date = clock.toLocalDate()
        return plans.flatMap { plan ->
            val recorded = logs.filter { it.data.optString("plan_id") == plan.id &&
                it.data.optString("local_date") == date.toString() && it.data.optString("status") != "undone" }
            val times = (if (scheduledOn(plan, date)) plan.times else emptyList()) + recorded.map { it.data.getString("scheduled_time") }
            times.distinct().map { time ->
                val log = recorded.find { it.data.getString("scheduled_time") == time }
                val state = if (log != null) log.data.getString("status")
                    else if (clock.toLocalTime().hour * 60 + clock.minute > LocalTime.parse(time).hour * 60 + LocalTime.parse(time).minute + 60) "overdue" else "pending"
                Todo(plan, date.toString(), time, state, log)
            }
        }.sortedWith(compareBy<Todo> { it.time }.thenBy { it.plan.medicineName })
    }
    fun scheduledOn(plan: Plan, date: LocalDate): Boolean {
        if (!plan.enabled || date < plan.startDate || (plan.endDate != null && date > plan.endDate) || plan.frequency == "as_needed") return false
        return when (plan.frequency) {
            "weekly" -> (date.dayOfWeek.value % 7) in plan.weekdays
            "interval" -> ChronoUnit.DAYS.between(plan.startDate, date) % plan.intervalDays == 0L
            else -> true
        }
    }

    data class Occurrence(val plan: Plan, val date: LocalDate, val time: String, val at: Instant)

    /** Scheduled doses whose local time falls in [from, to). */
    fun occurrences(plans: List<Plan>, zone: ZoneId, from: Instant, to: Instant): List<Occurrence> {
        val result = mutableListOf<Occurrence>()
        var date = from.atZone(zone).toLocalDate().minusDays(1)
        val last = to.atZone(zone).toLocalDate()
        while (date <= last) {
            for (plan in plans) {
                if (!scheduledOn(plan, date)) continue
                for (time in plan.times) {
                    val at = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
                    if (at >= from && at < to) result += Occurrence(plan, date, time, at)
                }
            }
            date = date.plusDays(1)
        }
        return result.sortedBy { it.at }
    }

    /** First scheduled date, used as the calendar event's DTSTART. */
    fun firstDate(plan: Plan): LocalDate? {
        var date = plan.startDate
        repeat(7) {
            if (scheduledOn(plan, date)) return date
            date = date.plusDays(1)
        }
        return null
    }

    private val days = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
    private val until = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun rrule(plan: Plan, zone: ZoneId): String? {
        if (!plan.enabled || plan.frequency == "as_needed" || plan.times.isEmpty()) return null
        val base = when (plan.frequency) {
            "weekly" -> if (plan.weekdays.isEmpty()) return null else "FREQ=WEEKLY;BYDAY=" + plan.weekdays.distinct().sorted().joinToString(",") { days[it] }
            "interval" -> if (plan.intervalDays <= 1) "FREQ=DAILY" else "FREQ=DAILY;INTERVAL=${plan.intervalDays}"
            else -> "FREQ=DAILY"
        }
        val end = plan.endDate ?: return base
        return "$base;UNTIL=" + until.format(end.atTime(23, 59, 59).atZone(zone).toInstant())
    }
}

data class EventSpec(
    val key: String,
    val title: String,
    val description: String,
    val start: Long,
    val rrule: String,
    val timezone: String,
    val alert: Boolean,
) {
    val fingerprint: String
        get() = java.security.MessageDigest.getInstance("SHA-256")
            .digest(listOf(title, description, start, rrule, timezone, alert).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
}

object CalendarPlanner {
    fun events(plans: List<Plan>, members: Set<String>, me: String, zone: ZoneId, alert: Boolean): List<EventSpec> =
        plans.filter { it.userId in members }.flatMap { plan ->
            val rule = Schedule.rrule(plan, zone) ?: return@flatMap emptyList()
            val first = Schedule.firstDate(plan) ?: return@flatMap emptyList()
            plan.times.map { time ->
                EventSpec(
                    key = "${plan.id}|$time",
                    title = title(plan, me),
                    description = description(plan),
                    start = first.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli(),
                    rrule = rule,
                    timezone = zone.id,
                    alert = alert,
                )
            }
        }

    fun title(plan: Plan, me: String): String {
        val dose = "${plan.medicineName}（${Texts.amount(plan.amount)}${plan.unit}）"
        return if (plan.userId == me) "吃药：$dose" else "${plan.username}吃药：$dose"
    }

    private fun description(plan: Plan) = buildString {
        append("用量：").append(Texts.amount(plan.amount)).append(plan.unit)
        Texts.meal(plan.mealRelation).takeIf { it.isNotEmpty() }?.let { append("，").append(it) }
        if (plan.notes.isNotBlank()) append("\n备注：").append(plan.notes.trim())
        append("\n请打开家药箱核对时间、用量和药盒后确认。日历提醒不代表已经服药。")
    }
}

data class Alarm(
    val key: String,
    val at: Long,
    val userId: String,
    val planId: String,
    val groupId: String,
    val date: String,
    val time: String,
    val kind: String,
    val medicine: String,
    val username: String,
)

object ReminderPlanner {
    val familyDelay: Duration = Duration.ofMinutes(30)
    val horizon: Duration = Duration.ofHours(48)
    // AlarmManager allows 500 pending alarms per app.
    const val LIMIT = 400

    /**
     * The person taking the medicine is reminded at the dose time. Family
     * members chosen in settings are reminded 30 minutes later, matching web push.
     */
    fun alarms(plans: List<Plan>, me: String, elderly: Boolean, members: Set<String>, zone: ZoneId, now: Instant): List<Alarm> {
        val end = now.plus(horizon)
        return Schedule.occurrences(plans, zone, now.minus(familyDelay), end).mapNotNull { o ->
            val p = o.plan
            val (kind, at) = when {
                p.userId == me -> "self" to o.at
                p.notifyFamily && !elderly && p.userId in members -> "family" to o.at.plus(familyDelay)
                else -> return@mapNotNull null
            }
            if (at <= now || at > end) return@mapNotNull null
            Alarm("${p.id}|${o.date}|${o.time}|$kind", at.toEpochMilli(), me, p.id, p.groupId, o.date.toString(), o.time, kind, p.medicineName, p.username)
        }.sortedBy { it.at }.take(LIMIT)
    }

    fun path(alarm: Alarm): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "/?plan=${enc(alarm.planId)}&date=${enc(alarm.date)}&time=${enc(alarm.time)}&group=${enc(alarm.groupId)}"
    }
}
