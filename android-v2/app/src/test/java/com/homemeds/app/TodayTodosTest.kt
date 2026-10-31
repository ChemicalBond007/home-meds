package com.homemeds.app

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.time.Instant

class TodayTodosTest {
    @Test fun recordedDosesSurviveDisabledPlanAndTimeChanges() {
        val p = plan(enabled = false)
        val log = DoseLog(json("id" to "d1", "plan_id" to p.id, "local_date" to "2026-10-31",
            "scheduled_time" to "07:30", "status" to "taken"))
        val items = Schedule.todayTodos(listOf(p), listOf(log), DEFAULT_ZONE, Instant.parse("2026-10-31T02:00:00Z"))
        assertEquals(1, items.size)
        assertEquals("taken", items[0].state)
        assertEquals("07:30", items[0].time)
    }
    @Test fun overdueMatchesWorkerSixtyMinuteThreshold() {
        val p = plan(times = listOf("08:00", "09:00", "10:00"))
        val items = Schedule.todayTodos(listOf(p), emptyList(), DEFAULT_ZONE, Instant.parse("2026-10-31T02:00:00Z"))
        assertEquals(listOf("overdue", "pending", "pending"), items.map { it.state })
    }
    @Test fun undoneRecordsDoNotCompleteATodo() {
        val log = DoseLog(json("id" to "d1", "plan_id" to "p1", "local_date" to "2026-10-31",
            "scheduled_time" to "08:00", "status" to "undone"))
        val items = Schedule.todayTodos(listOf(plan(times = listOf("08:00"))), listOf(log), DEFAULT_ZONE, Instant.parse("2026-10-31T00:00:00Z"))
        assertEquals("pending", items.single().state)
    }
    @Test fun rejectsSharedAndReservedNetworkAddresses() {
        listOf("100.64.0.1", "198.18.0.1", "192.0.0.1", "203.0.113.1", "2001:db8::1").forEach {
            assertFalse(it, UrlPolicy.isPublicAddress(InetAddress.getByName(it)))
        }
    }
}
