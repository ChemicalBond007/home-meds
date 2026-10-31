package com.homemeds.app

import com.homemeds.app.net.ThinkFilter
import com.homemeds.app.ui.parseProposal
import org.junit.Assert.*
import org.junit.Test

class BarcodeAndBoxTest {
    @Test fun validatesAndNormalizesGtin() {
        assertTrue(validGtin("6901234567892"))
        assertFalse(validGtin("6901234567893"))
        assertFalse(validGtin("123456789"))
        assertEquals("06901234567892", parseCode("6901234567892").gtin)
        assertEquals("unknown", parseCode("6901234567893").kind)
    }
    @Test fun parsesGs1DatesAndFields() {
        val code = parseCode("]C101069012345678921726020010BATCH\u001d21SERIAL")
        assertEquals("gs1", code.kind)
        assertEquals("2026-02-28", code.expiryDate)
        assertEquals("BATCH", code.batchNumber)
        assertEquals("SERIAL", code.serialNumber)
        assertEquals("2028-02-29", parseCode("(01)06901234567892(11)240101(15)280101(17)280229(10)B(21)S").expiryDate)
        assertNull(parseCode("(01)06901234567892(17)260230").expiryDate)
        assertEquals("unknown", parseCode("(01)06901234567893(17)261001").kind)
    }
    @Test fun extractsTraceAndDigitalLink() {
        val trace = "12345678901234567890"
        assertEquals("1234567", parseCode(trace).productKey)
        assertEquals(trace, parseCode("https://example.com/check?code=$trace").traceCode)
        assertEquals("06901234567892", parseCode("https://example.com/01/06901234567892").gtin)
        assertEquals("unknown", parseCode("https://example.com/check?code=${trace}1").kind)
        assertEquals(4096, parseCode("x".repeat(5000)).raw.length)
    }
    private fun box(id: String, expiry: String? = null, status: String = "active", remaining: Double = 2.0) =
        StockItem(json("id" to id, "product_id" to "med", "status" to status, "units_remaining" to remaining,
            "expiry_date" to expiry, "base_unit" to "片", "warehouse_name" to "家中", "metadata_version" to 3))
    @Test fun boxPreferenceNeverSilentlyFallsBackWhenUnavailable() {
        val a = box("a"); val b = box("b")
        assertEquals("a", initialDoseBox(PlanBoxes(listOf(a,b), "a", "b")))
        assertNull(initialDoseBox(PlanBoxes(listOf(b), "a", "b")))
        assertEquals("b", initialDoseBox(PlanBoxes(listOf(b), null, "b")))
        assertNull(initialDoseBox(PlanBoxes(listOf(b), null, null)))
        assertFalse(canConfirmDoseBox(null, "", true))
        assertFalse(canConfirmDoseBox(PlanBoxes(listOf(a), null, null), "a", false))
        assertTrue(canConfirmDoseBox(PlanBoxes(listOf(a), null, null), "", true))
        assertFalse(canConfirmDoseBox(PlanBoxes(listOf(a), null, null), "other", true))
    }
    @Test fun availableBoxesSortByExpiryAndExcludeEmpty() {
        val boxes = listOf(box("unknown"), box("late", "2027-01-01"), box("early", "2026-01-01"), box("empty", remaining = 0.0), box("discard", status = "discarded"))
        assertEquals(listOf("early","late","unknown"), availableBoxes(boxes, "med").map { it.id })
        assertTrue(expiryLabel(box("old", "2026-01-01"), "2026-10-31").contains("已过期"))
    }
    @Test fun reasoningNeverLeaksAcrossSplitTags() {
        val filter = ThinkFilter()
        assertEquals("你好", filter.push("你好<thi"))
        assertEquals("", filter.push("nk>private"))
        assertEquals("", filter.push("</thi"))
        assertEquals("正文", filter.push("nk>正文"))
        assertEquals("", filter.push("", true))
    }
    @Test fun rejectsDraftForDifferentMember() {
        val proposal = json("operation" to "create", "draft" to json("user_id" to "other"))
        assertThrows(IllegalArgumentException::class.java) { parseProposal(proposal, "me") }
    }
}
