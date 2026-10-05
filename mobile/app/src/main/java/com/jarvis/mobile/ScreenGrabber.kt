package com.jarvis.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Captures the screen as a JPEG (base64) so the model can SEE it, plus a text
 * summary comes from the AccessibilityService. Screenshots are downscaled to keep
 * vision-model costs tiny.
 */
class ScreenGrabber(context: Context, resultCode: Int, data: Intent) {
    private val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val projection: MediaProjection? = mpm.getMediaProjection(resultCode, data)

    private val metrics = DisplayMetrics()
    private val width: Int
    private val height: Int
    private val density: Int
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi
        projection?.registerCallback(object : MediaProjection.Callback() {}, null)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "JARVIS-screen", width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    /** Returns a downscaled JPEG as base64 (no data-uri prefix), or null. */
    fun capture(maxDim: Int = 1024, quality: Int = 70): String? {
        val ir = imageReader ?: return null
        val image = try { ir.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmpWidth = width + rowPadding / pixelStride
            var bitmap = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding > 0) bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
            if (scale < 1f)
                bitmap = Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    fun release() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
    }
}
