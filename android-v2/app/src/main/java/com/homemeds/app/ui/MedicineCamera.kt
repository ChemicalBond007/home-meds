package com.homemeds.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.homemeds.app.UserError
import java.io.File

class MedicinePhotoProvider : FileProvider()

internal class MedicineTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("药品照片", input)
        }
}

/** Only this private staging directory is shared with the selected system camera. */
internal class PendingCameraPhoto private constructor(private val context: Context, val file: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.medicine.photos", file)

    fun discard() {
        try { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        finally { file.delete() }
    }

    companion object {
        private fun directory(context: Context) = File(context.cacheDir, "medicine-camera")
        fun create(context: Context): PendingCameraPhoto {
            val directory = directory(context)
            if (!directory.isDirectory && !directory.mkdirs()) throw UserError("无法创建拍照临时文件，请从相册选图")
            val file = File.createTempFile("photo-", ".jpg", directory)
            return try { PendingCameraPhoto(context, file) } catch (e: Exception) { file.delete(); throw e }
        }

        fun restore(context: Context, path: String): PendingCameraPhoto {
            val file = File(path)
            if (file.canonicalFile.parentFile != directory(context).canonicalFile)
                throw UserError("拍照临时文件无效，请重新拍摄")
            return PendingCameraPhoto(context, file)
        }
    }
}
