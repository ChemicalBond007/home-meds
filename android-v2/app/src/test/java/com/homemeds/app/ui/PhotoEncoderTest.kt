package com.homemeds.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEncoderTest {
    private fun temporaryImage() = File.createTempFile("photo-test-", ".jpg", RuntimeEnvironment.getApplication().cacheDir)

    @Test fun largeNoisyImageIsJpegWithBoundedDimensionsAndPayload() {
        val file = temporaryImage()
        val bitmap = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
        val random = Random(42)
        val pixels = IntArray(4096 * 2048) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        bitmap.setPixels(pixels, 0, 4096, 0, 0, 4096, 2048)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        try {
            val photo = PhotoEncoder.encode(file)
            try {
                assertTrue(photo.dataUrl.startsWith("data:image/jpeg;base64,"))
                assertFalse(photo.dataUrl.contains('\n'))
                val bytes = Base64.getDecoder().decode(photo.dataUrl.substringAfter(','))
                assertTrue("JPEG payload: ${bytes.size}", bytes.size <= 400 * 1024)
                assertEquals(0xff, bytes[0].toInt() and 255)
                assertEquals(0xd8, bytes[1].toInt() and 255)
                assertTrue(maxOf(photo.preview.width, photo.preview.height) <= 320)
                assertEquals(2.0, photo.preview.width.toDouble() / photo.preview.height, .01)
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertTrue(maxOf(decoded.width, decoded.height) <= 1280)
                assertEquals(2.0, decoded.width.toDouble() / decoded.height, .01)
                println("PhotoEncoder noisy 4096x2048 -> ${decoded.width}x${decoded.height}, ${bytes.size} JPEG bytes")
                decoded.recycle()
            } finally { photo.preview.recycle() }
        } finally { file.delete() }
    }

    @Test fun allExifOrientationsAreAppliedAndMetadataIsStripped() {
        // Original corners: red, green / blue, yellow. Expected clockwise display corners.
        val corners = mapOf(
            1 to listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            2 to listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            3 to listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            4 to listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            5 to listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            6 to listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            7 to listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            8 to listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
        )
        for ((orientation, expected) in corners) {
            val file = temporaryImage()
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(80 * 40) { index ->
                val x = index % 80
                val y = index / 80
                if (y < 20) { if (x < 40) Color.RED else Color.GREEN }
                else { if (x < 40) Color.BLUE else Color.YELLOW }
            }
            bitmap.setPixels(pixels, 0, 80, 0, 0, 80, 40)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bitmap.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setAttribute(ExifInterface.TAG_ARTIST, "must-not-leave-device")
                saveAttributes()
            }
            try {
                val photo = PhotoEncoder.encode(file)
                try {
                    val p = photo.preview
                    assertEquals(if (orientation >= 5) 40 else 80, p.width)
                    assertEquals(if (orientation >= 5) 80 else 40, p.height)
                    val actual = listOf(p.getPixel(5, 5), p.getPixel(p.width - 6, 5),
                        p.getPixel(5, p.height - 6), p.getPixel(p.width - 6, p.height - 6))
                    actual.zip(expected).forEach { (a, e) ->
                        assertEquals("orientation $orientation red", Color.red(e).toDouble(), Color.red(a).toDouble(), 10.0)
                        assertEquals("orientation $orientation green", Color.green(e).toDouble(), Color.green(a).toDouble(), 10.0)
                        assertEquals("orientation $orientation blue", Color.blue(e).toDouble(), Color.blue(a).toDouble(), 10.0)
                    }
                    val bytes = Base64.getDecoder().decode(photo.dataUrl.substringAfter(','))
                    val encoded = ExifInterface(bytes.inputStream())
                    assertNull(encoded.getAttribute(ExifInterface.TAG_ARTIST))
                    assertEquals(0, encoded.rotationDegrees)
                } finally { photo.preview.recycle() }
            } finally { file.delete() }
        }
    }

    @Test fun incompressible1280SquareIsResizedFurtherToEnforcePayloadCap() {
        val file = temporaryImage()
        val bitmap = Bitmap.createBitmap(1280, 1280, Bitmap.Config.ARGB_8888)
        val random = Random(31415)
        bitmap.setPixels(IntArray(1280 * 1280) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) },
            0, 1280, 0, 0, 1280, 1280)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        try {
            val photo = PhotoEncoder.encode(file)
            try {
                val bytes = Base64.getDecoder().decode(photo.dataUrl.substringAfter(','))
                assertTrue(photo.preview.width <= 320)
                assertEquals(photo.preview.width, photo.preview.height)
                assertTrue("JPEG payload: ${bytes.size}", bytes.size <= 400 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                assertTrue(bounds.outWidth < 1280)
                assertEquals(bounds.outWidth, bounds.outHeight)
                println("PhotoEncoder noisy 1280x1280 -> ${bounds.outWidth}x${bounds.outHeight}, ${bytes.size} JPEG bytes")
            } finally { photo.preview.recycle() }
        } finally { file.delete() }
    }

    @Test fun sixPhotoBatchKeepsEveryPayloadBoundedAndOnlySmallPreviews() {
        val file = temporaryImage()
        val bitmap = Bitmap.createBitmap(1280, 1280, Bitmap.Config.ARGB_8888)
        val random = Random(1234)
        bitmap.setPixels(IntArray(1280 * 1280) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) },
            0, 1280, 0, 0, 1280, 1280)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        val selected = PhotoSelection()
        val start = System.nanoTime()
        try {
            repeat(com.homemeds.app.MAX_MEDICINE_PHOTOS) {
                val photo = PhotoEncoder.encode(file)
                assertTrue(selected.add(photo))
                assertTrue(Base64.getDecoder().decode(photo.dataUrl.substringAfter(',')).size <= 400 * 1024)
                assertTrue(maxOf(photo.preview.width, photo.preview.height) <= 320)
            }
            assertEquals(6, selected.photos.size)
            val previewBytes = selected.photos.sumOf { it.preview.allocationByteCount }
            assertTrue(previewBytes <= 6 * 320 * 320 * 4)
            println("PhotoEncoder six-photo batch: $previewBytes preview bytes, ${(System.nanoTime() - start) / 1_000_000} ms")
        } finally {
            selected.photos.forEach { it.preview.recycle() }
            file.delete()
        }
    }
}
