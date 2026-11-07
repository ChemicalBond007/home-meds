package com.homemeds.app.ui

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.homemeds.app.UserError
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemCameraUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.cacheDir, "medicine-camera")
    private var submitted: List<PhotoAttachment>? = null

    @Before fun configureFileProviderForHost() {
        val authority = "${context.packageName}.medicine.photos"
        val info = context.packageManager.resolveContentProvider(authority, android.content.pm.PackageManager.GET_META_DATA)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        info.loadXmlMetaData(context.packageManager, "android.support.FILE_PROVIDER_PATHS").use { xml ->
            var roots = 0
            while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name != "paths") {
                    assertEquals("cache-path", xml.name)
                    assertEquals("medicine-camera/", xml.getAttributeValue(null, "path"))
                    assertEquals("medicine_camera", xml.getAttributeValue(null, "name"))
                    roots++
                }
            }
            assertEquals(1, roots)
        }
        val provider = ShadowContentResolver.getProvider(Uri.parse("content://$authority"))!!
        @Suppress("UNCHECKED_CAST")
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .get(null) as MutableMap<String, Any>
        cache.clear()
        if (File.separatorChar != '\\') return
        // AndroidX still hard-codes '/' in belongsToRoot. Adapt only the path strategy
        // on Windows; keep the real provider, manifest, file streams and camera contract.
        val strategy = Class.forName("androidx.core.content.FileProvider\$PathStrategy")
        val portable = Proxy.newProxyInstance(strategy.classLoader, arrayOf(strategy)) { _, method, args ->
            when (method.name) {
                "getUriForFile" -> {
                    val file = (args!![0] as File).canonicalFile
                    require(file.parentFile == directory.canonicalFile)
                    Uri.Builder().scheme("content").authority(authority).appendPath("medicine_camera").appendPath(file.name).build()
                }
                "getFileForUri" -> {
                    val uri = args!![0] as Uri
                    require(uri.authority == authority && uri.pathSegments.size == 2 && uri.pathSegments[0] == "medicine_camera")
                    File(directory, uri.lastPathSegment!!).canonicalFile.also { require(it.parentFile == directory.canonicalFile) }
                }
                else -> error("unexpected path strategy method")
            }
        }
        cache[authority] = portable
        FileProvider::class.java.getDeclaredField("mLocalPathStrategy").apply { isAccessible = true }.set(provider, portable)
    }

    private inner class Results : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var permissionCode: Int? = null
        var cameraCode: Int? = null
        var albumCode: Int? = null
        var cameraIntent: Intent? = null
        var cameraUri: Uri? = null
        var missingCamera = false
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            when (contract) {
                is ActivityResultContracts.RequestPermission -> permissionCode = requestCode
                is MedicineTakePicture -> {
                    cameraCode = requestCode
                    cameraUri = input as Uri
                    cameraIntent = contract.createIntent(context, cameraUri!!)
                    if (missingCamera) throw ActivityNotFoundException()
                }
                is ActivityResultContracts.GetMultipleContents -> albumCode = requestCode
                else -> error("unexpected activity contract")
            }
        }
    }

    private fun photo() = PhotoAttachment("initial", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
    private fun screen(results: Results, initial: List<PhotoAttachment> = emptyList()) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides results) {
                PhotoCaptureDialog({}, initial) { submitted = it }
            }
        } }
    }

    private fun writePhoto(uri: Uri) {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
    }

    private fun waitForCount(count: Int) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("已添加 $count / 6 张").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("使用这些照片（$count 张）").assertIsEnabled()
    }

    @Test @Config(sdk = [29, 35])
    fun cameraIconRequestsPermissionThenUsesSystemCameraAndAddsMultipleAnglesWithoutSubmitting() {
        shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
        val results = Results()
        screen(results)
        compose.onNodeWithContentDescription("拍摄照片").performClick()
        compose.runOnIdle {
            assertNotNull(results.permissionCode)
            assertNull(results.cameraCode)
            shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
            results.dispatchResult(results.permissionCode!!, true)
            val intent = results.cameraIntent!!
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
            assertEquals("content", results.cameraUri!!.scheme)
            assertEquals("${context.packageName}.medicine.photos", results.cameraUri!!.authority)
            assertEquals(results.cameraUri, IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java))
            assertEquals(results.cameraUri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            writePhoto(results.cameraUri!!)
            results.dispatchResult(results.cameraCode!!, true)
        }
        waitForCount(1)
        compose.runOnIdle { assertTrue(directory.listFiles().orEmpty().isEmpty()); assertNull(submitted) }
        compose.onNodeWithContentDescription("拍摄照片").performClick()
        compose.runOnIdle { writePhoto(results.cameraUri!!); results.dispatchResult(results.cameraCode!!, true) }
        waitForCount(2)
        compose.onNodeWithContentDescription("使用这些照片（2 张）").performClick()
        compose.runOnIdle {
            assertEquals(2, submitted!!.size)
            assertTrue(submitted!!.all { it.dataUrl.startsWith("data:image/jpeg;base64,") })
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancellingSystemCameraKeepsExistingPhotoAndDeletesStagingFile() {
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        val results = Results()
        val initial = photo()
        screen(results, listOf(initial))
        compose.onNodeWithContentDescription("拍摄照片").performClick()
        compose.runOnIdle {
            assertEquals(1, directory.listFiles().orEmpty().size)
            results.dispatchResult(results.cameraCode!!, false)
        }
        compose.waitUntil(5000) { directory.listFiles().orEmpty().isEmpty() }
        compose.onNodeWithContentDescription("使用这些照片（1 张）").assertIsEnabled().performClick()
        compose.runOnIdle { assertSame(initial, submitted!!.single()) }
    }

    @Test fun permissionDenialAndMissingCameraLeaveAlbumAvailableAndPreservePhotos() {
        shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
        val results = Results()
        screen(results, listOf(photo()))
        compose.onNodeWithContentDescription("拍摄照片").performClick()
        compose.runOnIdle { results.dispatchResult(results.permissionCode!!, false) }
        compose.onNodeWithText("相机权限未开启，可从相册选图；已有照片会保留").assertExists()
        compose.onNodeWithContentDescription("从相册多选").assertIsEnabled()
        compose.runOnIdle {
            assertNull(results.cameraCode)
            results.missingCamera = true
            shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        }
        compose.onNodeWithContentDescription("拍摄照片").performClick()
        compose.onNodeWithText("手机未安装可用的相机，请从相册选图").assertExists()
        compose.onNodeWithContentDescription("从相册多选").assertIsEnabled()
        compose.onNodeWithContentDescription("使用这些照片（1 张）").assertIsEnabled()
        compose.runOnIdle { assertTrue(directory.listFiles().orEmpty().isEmpty()) }
    }

    @Test fun invalidCameraImageDoesNotReplacePreviouslySelectedPhotos() {
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        val results = Results()
        screen(results, listOf(photo()))
        compose.onNodeWithContentDescription("拍摄照片").performClick()
        compose.runOnIdle { results.dispatchResult(results.cameraCode!!, true) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("无法读取拍摄的照片，请重拍或从相册选图；已有照片会保留").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("使用这些照片（1 张）").assertIsEnabled()
        compose.runOnIdle { assertTrue(directory.listFiles().orEmpty().isEmpty()); assertNull(submitted) }
    }

    @Test fun photoGridScrollDoesNotMoveIconToolbarOrCoverTextAndCountBadgeUpdates() {
        val results = Results()
        screen(results, List(6) { photo() })
        val title = compose.onNodeWithText("药品照片").fetchSemanticsNode().boundsInRoot
        val count = compose.onNodeWithText("已添加 6 / 6 张").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("medicine_photos").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("photo_actions").fetchSemanticsNode().boundsInRoot
        assertTrue(title.bottom <= count.top)
        assertTrue(count.bottom <= grid.top)
        assertTrue(grid.bottom <= actions.top)
        compose.onNodeWithTag("medicine_photos").performScrollToIndex(5)
        assertEquals(actions, compose.onNodeWithTag("photo_actions").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("移除照片 6").assertIsDisplayed().performClick()
        compose.onNodeWithText("5").assertExists()
        compose.onNodeWithContentDescription("使用这些照片（5 张）").assertIsEnabled()
        compose.onNodeWithContentDescription("拍摄照片").assertIsEnabled()
        compose.onNodeWithText("从相册多选").assertDoesNotExist()
    }

    @Test fun albumMultiSelectRetainsExistingPhotosAndCapsCombinedBatchAtSix() {
        val results = Results()
        screen(results, listOf(photo(), photo()))
        val files = List(8) { File.createTempFile("album-test-", ".jpg", context.cacheDir).also { file -> writePhoto(Uri.fromFile(file)) } }
        try {
            compose.onNodeWithContentDescription("从相册多选").performClick()
            compose.runOnIdle { results.dispatchResult(results.albumCode!!, files.map(Uri::fromFile)) }
            waitForCount(6)
            compose.onNodeWithText("每次最多 6 张照片，超出的图片未添加").assertExists()
            compose.onNodeWithContentDescription("拍摄照片").assertIsNotEnabled()
            compose.onNodeWithContentDescription("从相册多选").assertIsNotEnabled()
            compose.onNodeWithContentDescription("使用这些照片（6 张）").performClick()
            compose.runOnIdle { assertEquals(6, submitted!!.size); assertEquals("initial", submitted!!.first().dataUrl) }
        } finally { files.forEach { it.delete() } }
    }

    @Test fun cameraFileProviderDoesNotExposeOtherPrivateCacheFiles() {
        val other = File(context.cacheDir, "unrelated-private-file.jpg")
        try {
            PendingCameraPhoto.restore(context, other.path)
            fail("must reject paths outside camera staging")
        } catch (_: UserError) { }
        val photo = PendingCameraPhoto.create(context)
        assertTrue(photo.file.exists())
        photo.discard()
        assertFalse(photo.file.exists())
    }
}
