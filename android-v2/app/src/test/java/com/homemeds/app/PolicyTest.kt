package com.homemeds.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UrlPolicyTest {
    @Test
    fun allowsPublicPages() {
        assertEquals("api.example.com", UrlPolicy.publicUrl("https://api.example.com/v1/chat").host)
        assertEquals("example.com", UrlPolicy.publicUrl("http://example.com/").host)
    }

    @Test
    fun rejectsPrivateOrUnusualTargets() {
        for (url in listOf(
            "http://localhost/", "https://127.0.0.1/", "https://example.com:8443/", "https://user:pw@example.com/",
            "https://printer.local/", "https://[::1]/", "ftp://example.com/", "https://intranet/", "https://metadata.google.internal/",
        )) assertThrows(url, UserError::class.java) { UrlPolicy.publicUrl(url) }
    }

    @Test
    fun classifiesResolvedAddresses() {
        assertFalse(UrlPolicy.isPublicAddress(InetAddress.getByName("10.0.0.1")))
        assertFalse(UrlPolicy.isPublicAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse(UrlPolicy.isPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(UrlPolicy.isPublicAddress(InetAddress.getByName("169.254.169.254")))
        assertFalse(UrlPolicy.isPublicAddress(InetAddress.getByName("::1")))
        assertFalse(UrlPolicy.isPublicAddress(InetAddress.getByName("fd00::1")))
        assertTrue(UrlPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(UrlPolicy.isPublicAddress(InetAddress.getByName("2001:4860:4860::8888")))
    }
}

class PathSegmentTest {
    @Test fun publicPagesExplicitlyDisableSystemProxies() {
        val http = com.homemeds.app.net.NativeHttp(10)
        val client = http.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(http) as okhttp3.OkHttpClient
        assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
    }
    @Test fun acceptsOnlyBoundedOpaqueIds() {
        for (id in listOf("plan_1-A", "a".repeat(100))) assertEquals(id, pathSegment(id))
        for (id in listOf(null, "", "a".repeat(101), "..", "../groups/x", "a/b", "a?b", "a#b", "%2F", "中文", "a b", "a\\\\b", "a\n")) {
            assertThrows(UserError::class.java) { pathSegment(id) }
        }
    }
}

class ParseTest {
    @Test
    fun parsesWorkerPlanRows() {
        val body = """[{"id":"p1","group_id":"g1","user_id":"u1","username":"奶奶","product_id":null,"medicine_name":"二甲双胍","amount":0.5,
            "unit":"片","times":"[\"08:00\",\"bad\",\"20:00\"]","frequency":"weekly","weekdays":"[1,3,9]","interval_days":1,
            "start_date":"2026-01-01","end_date":null,"meal_relation":"with","notes":"","enabled":1,"notify_family":0},
            {"id":"broken"}]"""
        val plans = parsePlans(body)
        assertEquals(1, plans.size)
        val p = plans[0]
        assertEquals(listOf("08:00", "20:00"), p.times)
        assertEquals(listOf(1, 3), p.weekdays)
        assertNull(p.endDate)
        assertFalse(p.notifyFamily)
        assertEquals("0.5", Texts.amount(p.amount))
        assertEquals("2", Texts.amount(2.0))
    }

    @Test
    fun findsFamilyTimezone() {
        val groups = """[{"id":"g0","timezone":"UTC"},{"id":"g1","timezone":"Asia/Urumqi"}]"""
        assertEquals("Asia/Urumqi", familyZone(groups, "g1").id)
        assertThrows(UserError::class.java) { familyZone(groups, "g9") }
    }
}
