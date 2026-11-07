package com.homemeds.app

import org.junit.Assert.*
import org.junit.Test

class MedicineExpiryTest {
    @Test fun preservesMonthPrecisionAndExpandsOnlyToTheActualMonthEnd() {
        for ((input, expected) in listOf("2028-02" to "2028-02-29", "2027-02" to "2027-02-28",
            "2028-04" to "2028-04-30", "2028-12" to "2028-12-31", "2100-02" to "2100-02-28")) {
            val expiry = medicineExpiry(input)!!
            assertEquals(input, expiry.text)
            assertEquals(expected, expiry.date.toString())
            assertTrue(expiry.monthOnly)
        }
    }

    @Test fun acceptsPrintedSeparatorsChineseDatesCompactDatesAndFullWidthDigits() {
        for (input in listOf("2028.06", "2028/6", "2028年6月", "202806", "２０２８．０６", " 2028 - 06 ")) {
            val expiry = medicineExpiry(input)!!
            assertEquals("2028-06", expiry.text)
            assertEquals("2028-06-30", expiry.date.toString())
            assertTrue(expiry.monthOnly)
        }
        for (input in listOf("2028-06-09", "2028.6.9", "2028年6月9日", "20280609")) {
            val expiry = medicineExpiry(input)!!
            assertEquals("2028-06-09", expiry.text)
            assertFalse(expiry.monthOnly)
        }
        assertNull(medicineExpiry(" "))
    }

    @Test fun invalidAndAmbiguousDatesAreRejectedRatherThanRolledForward() {
        for (input in listOf("2028", "06/28", "2028-13", "2028-00", "2027-02-29", "2028-02-30",
            "2028-06-00", "2028-06-", "2028-06-31", "0000-02", "有效期24个月", "生产日期2026-11")) {
            try { medicineExpiry(input); fail("must reject $input") } catch (_: UserError) { }
        }
    }
}
