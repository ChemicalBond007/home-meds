package com.homemeds.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.homemeds.app.*
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackageExpiryUiTest {
    @get:Rule val compose = createComposeRule()
    private val gate = CompletableDeferred<Unit>()
    private var saved: JSONObject? = null
    private var calls = 0
    private var cancelled = 0
    private val files = mutableListOf<File>()

    private class Album : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var code = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            code = requestCode
        }
    }

    @After fun cleanup() { files.forEach { it.delete() } }

    private fun editor(album: Album, details: JSONObject) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides album) {
                var busy by remember { mutableStateOf(false) }
                EditForm("核对药盒", listOf(FormField("expiry_date", "有效期", "expiry"),
                    FormField("batch_number", "批号", "nullable"), FormField("units_per_package", "每盒数量", "number")),
                    json("expiry_date" to "2027-06-10", "batch_number" to "OLD", "units_per_package" to 24, "expected_version" to 7),
                    busy, {}, extra = { values ->
                        PackagePhotoControl("g1", true, { busy = it }, recognize = { images, group, emit ->
                            assertEquals("g1", group)
                            assertEquals(1, images.size)
                            assertTrue(images.single().startsWith("data:image/jpeg;base64,"))
                            calls++
                            try { gate.await(); emit("result", details) }
                            finally { if (!gate.isCompleted) cancelled++ }
                        }) { result ->
                            result.nullable("expiry_date")?.let { values["expiry_date"] = it }
                            result.nullable("batch_number")?.let { values["batch_number"] = it }
                        }
                    }) { saved = it }
            }
        } }
    }

    private fun selectCloseup(album: Album) {
        val file = File.createTempFile("expiry-ui-", ".jpg", RuntimeEnvironment.getApplication().cacheDir)
        files += file
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        compose.onNodeWithText("拍照识别有效期 / 批号").performScrollTo().performClick()
        compose.onNodeWithContentDescription("从相册多选").performClick()
        compose.runOnIdle { album.dispatchResult(album.code, listOf(Uri.fromFile(file))) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("已添加 1 / 6 张").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(0, calls); assertNull(saved) }
        compose.onNodeWithContentDescription("使用这些照片（1 张）").performClick()
    }

    @Test fun closeupPrefillsMonthThenSavesMonthEndOnlyAfterConfirmationWithoutChangingQuantityOrVersion() {
        val album = Album()
        editor(album, json("expiry_date" to "2028-02", "batch_number" to null))
        selectCloseup(album)
        compose.onNodeWithText("核对并保存").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); assertNull(saved); gate.complete(Unit) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("包装仅标年月，确认保存时按月末 2028-02-29 记录。请核对实物。").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("核对并保存").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("2028-02-29", saved!!.getString("expiry_date"))
            assertEquals("OLD", saved!!.getString("batch_number"))
            assertEquals(24.0, saved!!.getDouble("units_per_package"), 0.0)
            assertEquals(7, saved!!.getInt("expected_version"))
        }
    }

    @Test fun cancellingDateRecognitionKeepsOriginalDateAndBatchAndAllowsManualSave() {
        val album = Album()
        editor(album, json("expiry_date" to "2028-02", "batch_number" to "NEW"))
        selectCloseup(album)
        compose.onNodeWithText("取消日期识别").performScrollTo().performClick()
        compose.onNodeWithText("核对并保存").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals("2027-06-10", saved!!.getString("expiry_date"))
            assertEquals("OLD", saved!!.getString("batch_number"))
        }
    }

    @Test fun unreadablePhotosDoNotEraseExistingFieldsOrSaveAutomatically() {
        val album = Album()
        editor(album, json("expiry_date" to null, "batch_number" to null))
        selectCloseup(album)
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("未识别到清楚的有效期或批号", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("核对并保存").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("2027-06-10", saved!!.getString("expiry_date"))
            assertEquals("OLD", saved!!.getString("batch_number"))
        }
    }

    @Test fun existingTraceWithoutProductMappingShowsDrugAndExactBoxButDisablesDuplicateIntake() {
        val trace = "12345678901234567890"
        val product = Product(json("id" to "p1", "name" to "已有药品", "base_unit" to "片", "units_per_package" to 24))
        val box = json("id" to "b1", "product_id" to "p1", "trace_code" to trace, "expiry_date" to "2028-06-30",
            "batch_number" to "LOT-1", "units_remaining" to 0, "status" to "empty")
        var opened = 0
        compose.setContent { MaterialTheme { Modal("扫码结果", {}) {
            ScannedMedicineResult(AppState(products = listOf(product)), json("product" to null, "box" to box), trace) { opened++ }
        } } }
        compose.onNodeWithText("已有药品").assertExists()
        compose.onNodeWithText("有效期：2028-06-30").assertExists()
        compose.onNodeWithText("药盒状态：用尽").assertExists()
        compose.onNodeWithText("尚未关联药品，可手动录入，或让 AI 检索后逐项核对。").assertDoesNotExist()
        compose.onNodeWithText("进入入库向导").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, opened) }
    }

    @Test fun invalidMonthCannotBeSavedThroughTheRealMetadataForm() {
        compose.setContent { MaterialTheme {
            EditForm("日期", listOf(FormField("expiry_date", "有效期", "expiry")),
                json("expiry_date" to "2028-13"), false, {}) { saved = it }
        } }
        compose.onNodeWithText("核对并保存").performScrollTo().performClick()
        compose.onNodeWithText("有效期日期无效，请核对实物上的年月日").assertExists()
        compose.runOnIdle { assertNull(saved) }
    }
}
