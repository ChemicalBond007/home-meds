package com.homemeds.app

import org.junit.Assert.*
import org.junit.Test

class MedicineCodeResolutionTest {
    private val trace = "12345678901234567890"
    private val product = Product(json("id" to "p1", "name" to "已入库药", "base_unit" to "片", "units_per_package" to 24))
    private fun box(status: String = "active") = StockItem(json("id" to "b1", "product_id" to "p1", "trace_code" to trace,
        "expiry_date" to "2028-06-30", "batch_number" to "LOT-1", "units_remaining" to 12, "status" to status))

    @Test fun exactExistingBoxResolvesDrugEvenWithoutProductPrefixMapping() {
        val response = json("product" to null, "box" to box().data)
        val before = response.toString()
        val resolved = resolveMedicineCode(response, trace, listOf(product), emptyList())
        assertEquals("p1", resolved.getJSONObject("product").getString("id"))
        assertEquals("b1", resolved.getJSONObject("box").getString("id"))
        assertEquals(before, response.toString())
    }

    @Test fun exactBoxWinsOverWrongPrefixMappingAndCanRecoverFromCurrentFamilyStock() {
        val wrong = json("id" to "wrong", "name" to "其他药")
        for (status in listOf("active", "empty", "discarded")) {
            val resolved = resolveMedicineCode(json("product" to wrong), "https://example.test/scan?code=$trace",
                listOf(product), listOf(box(status)))
            assertEquals("p1", resolved.getJSONObject("product").getString("id"))
            assertEquals(status, resolved.getJSONObject("box").getString("status"))
        }
        val missing = resolveMedicineCode(json("box" to box().data, "product" to wrong), trace, emptyList(), emptyList())
        assertTrue(missing.isNull("product"))
        assertEquals("b1", missing.getJSONObject("box").getString("id"))
    }

    @Test fun productBarcodeAndUnknownTraceNeverMatchAnArbitraryBox() {
        val response = json("product" to product.data)
        val ordinary = resolveMedicineCode(response, "6901234567892", listOf(product), listOf(box()))
        assertTrue(ordinary.isNull("box"))
        assertEquals("p1", ordinary.getJSONObject("product").getString("id"))
        assertNull(knownTraceBox("12345678901234567891", listOf(box())))
    }

    @Test fun scannerPrefersExactTraceOverProductBarcodeAndRejectsMultipleBoxes() {
        val gtin = "6901234567892"
        assertTrue(validGtin(gtin))
        assertEquals(trace, preferredMedicineCode(listOf(gtin, trace)))
        val url = "https://example.test/?code=$trace"
        assertEquals(url, preferredMedicineCode(listOf(gtin, url)))
        assertEquals(trace, preferredMedicineCode(listOf(trace, trace, url)))
        assertNull(preferredMedicineCode(listOf(trace, "12345678901234567891")))
        assertEquals(gtin, preferredMedicineCode(listOf("irrelevant QR", gtin)))
    }
}
