package com.homemeds.app.ui

import android.Manifest
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.homemeds.app.UserError
import com.homemeds.app.MAX_MEDICINE_PHOTOS
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

data class PhotoAttachment(val dataUrl: String, val preview: Bitmap) {
    override fun toString() = "PhotoAttachment([image])"
}

internal class PhotoSelection(initial: List<PhotoAttachment> = emptyList()) {
    init { require(initial.size <= MAX_MEDICINE_PHOTOS) }
    var photos by mutableStateOf(initial.toList())
        private set
    val canAdd get() = photos.size < MAX_MEDICINE_PHOTOS
    fun add(photo: PhotoAttachment): Boolean {
        if (!canAdd) return false
        photos = photos + photo
        return true
    }
    fun remove(index: Int) { photos = photos.filterIndexed { i, _ -> i != index } }
}

@Composable internal fun PhotoAttachmentStrip(photos: List<PhotoAttachment>, enabled: Boolean = true, remove: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(photos) { index, photo ->
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Image(photo.preview.asImageBitmap(), "药品照片 ${index + 1}", Modifier.fillMaxWidth().height(120.dp))
                Action("移除照片 ${index + 1}", secondary = true, enabled = enabled) { remove(index) }
            }
        }
    }
}

/** Re-encoding strips EXIF/location metadata. Limit input size and decode at a sampled resolution. */
internal object PhotoEncoder {
    suspend fun read(context: Context, uri: Uri): PhotoAttachment = withContext(Dispatchers.IO) {
        val file = File.createTempFile("medicine-import-", ".image", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw UserError("无法读取图片")
            input.use { source -> file.outputStream().use { target ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 25L * 1024 * 1024) throw UserError("图片超过 25MB，请选择较小的图片")
                    target.write(buffer, 0, count)
                }
            } }
            encode(file)
        } finally { file.delete() }
    }

    fun encode(file: File): PhotoAttachment {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UserError("无法解码图片，请选择 JPEG 或 PNG 图片")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        var bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw UserError("无法解码图片")
        try {
            val exif = ExifInterface(file)
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
            var longest = 1280
            while (true) {
                val scale = minOf(1.0, longest.toDouble() / maxOf(bitmap.width, bitmap.height))
                if (scale < 1) {
                    val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                        (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                    if (resized !== bitmap) bitmap.recycle()
                    bitmap = resized
                }
                val bytes = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    out.toByteArray()
                }
                if (bytes.size <= 400 * 1024) {
                    // Keep only small previews when six full-resolution images are attached.
                    val previewScale = minOf(1.0, 320.0 / maxOf(bitmap.width, bitmap.height))
                    val preview = if (previewScale < 1) Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * previewScale).roundToInt().coerceAtLeast(1),
                        (bitmap.height * previewScale).roundToInt().coerceAtLeast(1), true) else bitmap
                    if (preview !== bitmap) bitmap.recycle()
                    return PhotoAttachment("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), preview)
                }
                longest = (maxOf(bitmap.width, bitmap.height) * .8).toInt().coerceAtLeast(1)
            }
        } catch (e: Throwable) { bitmap.recycle(); throw e }
    }
}

@Composable fun PhotoCaptureDialog(dismiss: () -> Unit, initialPhotos: List<PhotoAttachment> = emptyList(),
    attached: (List<PhotoAttachment>) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val selection = remember { PhotoSelection(initialPhotos) }
    var error by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var pickingAlbum by rememberSaveable { mutableStateOf(false) }
    var requestingPermission by rememberSaveable { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = processing || pickingAlbum || requestingPermission || pendingPath != null
    val onAttached by rememberUpdatedState(attached)
    fun addPhoto(photo: PhotoAttachment) {
        if (!selection.add(photo)) {
            photo.preview.recycle()
            error = "每次最多添加 $MAX_MEDICINE_PHOTOS 张照片，请先移除不需要的照片"
        }
    }
    val camera = rememberLauncherForActivityResult(MedicineTakePicture()) { success ->
        val path = pendingPath
        if (path != null) scope.launch {
            processing = true
            var photo: PendingCameraPhoto? = null
            try {
                photo = PendingCameraPhoto.restore(context, path)
                if (success) addPhoto(PhotoEncoder.read(context, photo.uri))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "无法读取拍摄的照片，请重拍或从相册选图；已有照片会保留" }
            finally {
                try { photo?.discard() }
                finally { pendingPath = null; processing = false }
            }
        }
    }
    fun launchCamera() {
        if (!selection.canAdd) return
        var photo: PendingCameraPhoto? = null
        try {
            photo = PendingCameraPhoto.create(context)
            pendingPath = photo.file.path
            camera.launch(photo.uri)
        } catch (_: ActivityNotFoundException) {
            photo?.discard(); pendingPath = null
            error = "手机未安装可用的相机，请从相册选图"
        } catch (_: Exception) {
            photo?.discard(); pendingPath = null
            error = "无法启动相机，请检查相机权限或从相册选图"
        }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingPermission = false
        if (granted) launchCamera() else error = "相机权限未开启，可从相册选图；已有照片会保留"
    }
    val album = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        pickingAlbum = false
        if (uris.isNotEmpty()) scope.launch {
            processing = true
            error = null
            val chosen = uris.distinct()
            val remaining = MAX_MEDICINE_PHOTOS - selection.photos.size
            try {
                for (uri in chosen.take(remaining)) {
                    try { addPhoto(PhotoEncoder.read(context, uri)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "部分图片无法读取，请重选 JPEG 或 PNG 图片；已添加的照片会保留" }
                }
                if (chosen.size > remaining) error = "每次最多 $MAX_MEDICINE_PHOTOS 张照片，超出的图片未添加"
            }
            finally { processing = false }
        }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).widthIn(max = 680.dp).fillMaxHeight(.92f), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("药品照片", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(dismiss, Modifier.size(touchHeight), enabled = !busy) { Icon(Icons.Default.Close, "关闭照片选择") }
                }
                Text("已添加 ${selection.photos.size} / $MAX_MEDICINE_PHOTOS 张", color = Muted)
                Text("同一药盒可分次拍摄多个角度，请勿混拍不同药品或批次。提交查询或发送消息时才发送给 AI，请避免拍入个人信息。",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                if (selection.photos.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("尚未添加照片", color = Muted)
                } else LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f).fillMaxWidth().testTag("medicine_photos"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(selection.photos) { index, photo ->
                        Column {
                            Image(photo.preview.asImageBitmap(), "药品照片 ${index + 1}", Modifier.fillMaxWidth().height(140.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("照片 ${index + 1}", Modifier.weight(1f), color = Muted)
                                IconButton({ selection.remove(index); error = null }, Modifier.size(touchHeight), enabled = !busy) {
                                    Icon(Icons.Default.Close, "移除照片 ${index + 1}")
                                }
                            }
                        }
                    }
                }
                if (processing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth().testTag("photo_actions"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton({
                        if (!busy && selection.canAdd) {
                            error = null
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                            else try { requestingPermission = true; request.launch(Manifest.permission.CAMERA) }
                            catch (_: Exception) { requestingPermission = false; error = "无法请求相机权限，请从相册选图" }
                        }
                    }, Modifier.size(touchHeight), enabled = !busy && selection.canAdd) { Icon(Icons.Default.PhotoCamera, "拍摄照片") }
                    IconButton({
                        if (!busy && selection.canAdd) try {
                            pickingAlbum = true; error = null; album.launch("image/*")
                        } catch (_: Exception) { pickingAlbum = false; error = "无法打开相册，请重试或拍照" }
                    }, Modifier.size(touchHeight), enabled = !busy && selection.canAdd) { Icon(Icons.Default.PhotoLibrary, "从相册多选") }
                    Spacer(Modifier.weight(1f))
                    BadgedBox(badge = { if (selection.photos.isNotEmpty()) Badge { Text("${selection.photos.size}") } }) {
                        FilledIconButton({ onAttached(selection.photos.toList()) }, Modifier.size(touchHeight),
                            enabled = !busy && selection.photos.isNotEmpty()) {
                            Icon(Icons.Default.Check, "使用这些照片（${selection.photos.size} 张）")
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true)
                pendingPath?.let { path -> runCatching { PendingCameraPhoto.restore(context, path).discard() } }
        }
    }
}
