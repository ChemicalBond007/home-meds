package com.homemeds.app.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.homemeds.app.MAX_MEDICINE_PHOTOS
import com.homemeds.app.json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MedicinePhotoUiTest {
    @get:Rule val compose = createComposeRule()
    @Before fun setup() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA)
    }
    private fun photo(id: Int) = PhotoAttachment("data:image/jpeg;base64,test-$id",
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

    @Test fun homeEntryOffersDirectPhotoLookupWithoutRemovingBarcodeRoute() {
        var photos = 0
        var scans = 0
        compose.setContent { MaterialTheme { MedicineLookupEntry(true, { scans++ }, { photos++ }) } }
        compose.onNodeWithText("扫码查药 / 入库").performClick()
        compose.onNodeWithText("扫描条码 / 追溯码").assertExists()
        compose.onNodeWithText("拍照查药 / 入库（多角度）").performClick()
        compose.runOnIdle { assertEquals(1, photos); assertEquals(0, scans) }
        compose.onNodeWithText("选择查药方式").assertDoesNotExist()
        compose.onNodeWithText("扫码查药 / 入库").performClick()
        compose.onNodeWithText("扫描条码 / 追溯码").performClick()
        compose.runOnIdle { assertEquals(1, scans) }
    }

    @Test fun disabledHomeEntryDoesNotOpenLookup() {
        compose.setContent { MaterialTheme { MedicineLookupEntry(false, {}, {}) } }
        compose.onNodeWithText("扫码查药 / 入库").assertIsNotEnabled()
        compose.onNodeWithText("选择查药方式").assertDoesNotExist()
    }

    @Test fun photoPickerDoesNotSubmitUntilExplicitConfirmationAndCanRemoveAnAngle() {
        val initial = listOf(photo(1), photo(2))
        var submitted: List<PhotoAttachment>? = null
        compose.setContent { MaterialTheme { PhotoCaptureDialog({}, initial) { submitted = it } } }
        compose.onNodeWithText("已添加 2 / 6 张").assertExists()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithContentDescription("使用这些照片（2 张）").assertIsDisplayed()
        compose.onNodeWithContentDescription("移除照片 1").assertIsDisplayed().performClick()
        compose.onNodeWithText("已添加 1 / 6 张").assertExists()
        compose.onNodeWithContentDescription("使用这些照片（1 张）").performClick()
        compose.runOnIdle {
            assertEquals(1, submitted!!.size)
            assertSame(initial[1], submitted!!.single())
            assertEquals(2, initial.size)
        }
    }

    @Test fun fullPhotoBatchCannotAddMore() {
        val photos = List(MAX_MEDICINE_PHOTOS) { photo(it) }
        compose.setContent { MaterialTheme { PhotoCaptureDialog({}, photos) {} } }
        compose.onNodeWithText("已添加 6 / 6 张").assertExists()
        compose.onNodeWithContentDescription("拍摄照片").assertIsNotEnabled()
        compose.onNodeWithContentDescription("从相册多选").assertIsNotEnabled()
        compose.onNodeWithContentDescription("使用这些照片（6 张）").assertIsEnabled()
    }

    @Test fun emptyPhotoPickerCannotSubmit() {
        compose.setContent { MaterialTheme { PhotoCaptureDialog({}) {} } }
        compose.onNodeWithText("已添加 0 / 6 张").assertExists()
        compose.onNodeWithContentDescription("使用这些照片（0 张）").assertIsNotEnabled()
        compose.onNodeWithContentDescription("拍摄照片").assertIsEnabled()
        compose.onNodeWithContentDescription("从相册多选").assertIsEnabled()
    }

    @Test fun photoResultDoesNotSaveUntilIntakeIsChosen() {
        var intake = 0
        val draft = json("name" to "测试药", "expiry_date" to "2028-06-30", "batch_number" to "B202611")
        compose.setContent { MaterialTheme { Modal("照片识药结果", {}) {
            MedicinePhotoResult(AppState(), draft) { intake++ }
        } } }
        compose.onNodeWithText("有效期：2028-06-30").assertExists()
        compose.onNodeWithText("批号：B202611").assertExists()
        compose.runOnIdle { assertEquals(0, intake) }
        compose.onNodeWithText("核对并入库").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, intake) }
    }

    @Test fun photoSelectionSupportsContinuousCaptureRemovalAndRefillWithoutMutatingSeed() {
        val seed = mutableListOf(photo(0))
        val selection = PhotoSelection(seed)
        seed.clear()
        assertEquals(1, selection.photos.size)
        repeat(MAX_MEDICINE_PHOTOS - 1) { assertTrue(selection.add(photo(it + 1))) }
        assertFalse(selection.canAdd)
        assertFalse(selection.add(photo(99)))
        val snapshot = selection.photos.toList()
        selection.remove(2)
        assertTrue(selection.canAdd)
        assertEquals(listOf(0, 1, 3, 4, 5).map { "data:image/jpeg;base64,test-$it" }, selection.photos.map { it.dataUrl })
        assertTrue(selection.add(photo(6)))
        assertEquals(6, selection.photos.size)
        assertEquals("data:image/jpeg;base64,test-6", selection.photos.last().dataUrl)
        assertEquals("data:image/jpeg;base64,test-2", snapshot[2].dataUrl)
    }
}
