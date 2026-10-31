package com.homemeds.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

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

class KeyPolicyTest {
    private val session = AiSession(
        "s-1", Long.MAX_VALUE, "sk-llm", "https://api.example.com/v1".toHttpUrl(), "tv-key", "https://api.tavily.com".toHttpUrl(),
    )

    @Test
    fun injectsKeysOnlyUnderConfiguredBase() {
        assertEquals("Bearer sk-llm", KeyPolicy.resolve("Bearer hm-key:s-1:llm", "https://api.example.com/v1/chat/completions".toHttpUrl(), session))
        assertEquals("Bearer tv-key", KeyPolicy.resolve("Bearer hm-key:s-1:tavily", "https://api.tavily.com/search".toHttpUrl(), session))
        for (url in listOf("https://api.example.com/v2/chat", "https://api.example.com/v1x", "https://evil.example/v1/chat", "http://api.example.com/v1/chat")) {
            assertThrows(url, UserError::class.java) { KeyPolicy.resolve("Bearer hm-key:s-1:llm", url.toHttpUrl(), session) }
        }
        assertThrows(UserError::class.java) { KeyPolicy.resolve("Bearer hm-key:s-1:tavily", "https://api.example.com/v1/chat".toHttpUrl(), session) }
    }

    @Test
    fun rejectsForeignOrMalformedReferences() {
        val url = "https://api.example.com/v1/chat".toHttpUrl()
        assertThrows(UserError::class.java) { KeyPolicy.resolve("Bearer hm-key:other:llm", url, session) }
        assertThrows(UserError::class.java) { KeyPolicy.resolve("Bearer hm-key:s-1:admin", url, session) }
        assertThrows(UserError::class.java) {
            KeyPolicy.resolve("Bearer hm-key:s-1:tavily", "https://api.tavily.com/search".toHttpUrl(), session.copy(tavilyKey = null))
        }
    }

    @Test
    fun rootBaseAllowsAnyPathOnThatHost() {
        val root = session.copy(llmBase = "https://api.deepseek.com".toHttpUrl())
        assertTrue(KeyPolicy.within("https://api.deepseek.com/chat/completions".toHttpUrl(), root.llmBase))
        assertFalse(KeyPolicy.within("https://deepseek.com/chat/completions".toHttpUrl(), root.llmBase))
    }
}

class SealedTest {
    private val fixture = JSONObject(javaClass.classLoader!!.getResource("sealed-fixture.json")!!.readText())
    private val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(fixture.getString("pkcs8"))))

    @Test
    fun opensCredentialsSealedByWorkerWebCrypto() {
        val payload = JSONObject(Sealed.open(key, fixture.getJSONObject("sealed")))
        val expected = fixture.getJSONObject("payload")
        assertEquals(expected.getString("llm_key"), payload.getString("llm_key"))
        val session = AiSession.parse(expected.getString("session_id"), payload)
        assertEquals("api.example.com", session.llmBase.host)
        assertNull(session.tavilyKey)
        assertEquals(expected.getLong("expires_at"), session.expiresAt)
        assertThrows(UserError::class.java) { AiSession.parse("another-session", payload) }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val sealed = JSONObject(fixture.getJSONObject("sealed").toString())
        val bytes = Base64.getDecoder().decode(sealed.getString("ciphertext"))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        sealed.put("ciphertext", Base64.getEncoder().encodeToString(bytes))
        assertThrows(GeneralSecurityException::class.java) { Sealed.open(key, sealed) }
        assertThrows(UserError::class.java) { Sealed.open(key, JSONObject(sealed.toString()).put("alg", "other")) }
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
