package com.homemeds.app

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GroupJoinTest {
    private fun payload(code: String = "AbC_-09") = json("type" to "home-meds.join", "version" to 1, "code" to code).toString()
    @Test fun sharedWebFixtureSurvivesRealQrEncodingAndDecoding() {
        val fixture = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "tests/fixtures/group-join-qr.json") }.first { it.isFile }.readText().trim()
        val matrix = QRCodeWriter().encode(fixture, BarcodeFormat.QR_CODE, 280, 280)
        val pixels = IntArray(280 * 280) { if (matrix[it % 280, it / 280]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(280, 280, pixels)))).text
        assertEquals("DEMO-LOCAL-ONLY", groupJoinCodeFromQr(decoded))
        assertEquals("AbC_-09", groupJoinCodeFromQr(payload()))
    }
    @Test fun unrelatedMalformedAndUnsupportedQrCodesAreNeverUsedAsInvitations() {
        for (raw in listOf("AbC_-09", "https://unrelated.example/join?code=abc", "81234567890123456789", "{bad",
            """{"type":"other","version":1,"code":"abc"}""", """{"type":"home-meds.join","version":2,"code":"abc"}""",
            """{"type":"home-meds.join","version":1,"code":123}""", payload("   "), payload("x".repeat(101)), "x".repeat(4097)))
            assertNull(groupJoinCodeFromQr(raw))
        assertEquals("x".repeat(100), groupJoinCodeFromQr(payload("x".repeat(100))))
    }
    @Test fun multipleInvitationsAreAmbiguousButRepeatedSameCodeAndUnrelatedCodesAreSafe() {
        assertNull(preferredGroupJoinCode(listOf(payload("one"), payload("two"))))
        assertEquals("one", preferredGroupJoinCode(listOf(payload("one"), payload("one"), "unrelated")))
        assertNull(preferredGroupJoinCode(emptyList()))
    }
    @Test fun registrationRequiresCodeAndProducesOnlyTheExistingRegistrationFields() {
        for (code in listOf("", "  ", "x".repeat(101))) {
            try { memberRegistrationBody("新成员", code, false); fail("invalid invitation must fail") } catch (_: UserError) { }
        }
        val body = memberRegistrationBody(" 新成员 ", " AbC_-09 ", true)
        assertEquals("新成员", body.getString("username"))
        assertEquals("AbC_-09", body.getString("join_code"))
        assertTrue(body.getBoolean("is_elderly"))
        assertEquals(3, body.length())
    }
}
