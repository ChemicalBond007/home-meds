package com.homemeds.app.ui

import com.homemeds.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntakeDraftTest {
    private fun product(id: String, spec: String = "10mg", manufacturer: String = "厂家甲") = Product(json(
        "id" to id, "name" to "测试药", "specification" to spec, "manufacturer" to manufacturer,
        "base_unit" to "片", "units_per_package" to 24))

    @Test fun matchesOnlyUnambiguousProductsAndRespectsSpecificationAndManufacturer() {
        val products = listOf(product("a"), product("b", "20mg"), product("c", manufacturer = "厂家乙"))
        assertNull(matchMedicineDraft(json("name" to "测试药"), products))
        assertNull(matchMedicineDraft(json("name" to "新药"), products))
        assertNull(matchMedicineDraft(JSONObject(), products))
        val draft = json("name" to " 测试药 ", "specification" to "10mg", "manufacturer" to "厂家甲")
        assertEquals("a", matchMedicineDraft(draft, products)!!.id)
        assertNull(matchMedicineDraft(draft.put("specification", "30mg"), products))
    }

    @Test fun prefillsAllContainerFieldsWithoutOverwritingRecognizedQuantity() {
        val seed = prepareIntakeDraft(json("name" to "测试药", "code" to "12345678901234567890",
            "expiry_date" to "2028-06-30", "batch_number" to "B202611", "units_per_package" to 12), listOf(product("a")))
        assertEquals("a", seed.product!!.id)
        assertEquals("12345678901234567890", seed.code)
        assertEquals("2028-06-30", seed.expiry)
        assertEquals("B202611", seed.batch)
        assertEquals("12", seed.size)
    }

    @Test fun repeatedRecognitionDoesNotReusePreviousBoxMetadataOrQuantity() {
        val first = prepareIntakeDraft(json("name" to "测试药", "expiry_date" to "2028-01-01",
            "batch_number" to "OLD", "code" to "12345678901234567890", "units_per_package" to 12), emptyList())
        val second = prepareIntakeDraft(json("name" to "新药", "expiry_date" to null,
            "batch_number" to null, "code" to null, "units_per_package" to null), emptyList())
        assertEquals("OLD", first.batch)
        assertNull(second.product)
        assertEquals("", second.code)
        assertEquals("", second.expiry)
        assertEquals("", second.batch)
        assertEquals("", second.size)
        val existing = prepareIntakeDraft(json("name" to "测试药"), listOf(product("a")))
        assertEquals("24", existing.size)
    }
}
