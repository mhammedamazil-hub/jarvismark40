package com.jarvis.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Camera plugin — "JARVIS can see my camera when I show it."
 *
 * Captures a still frame (front or back) as a downscaled JPEG and hands the base64 to the
 * agent / live-ask flow so the vision model can look at what you're showing it. Uses CameraX
 * (handles device quirks) with an on-demand [ImageCapture], so there's no continuous preview
 * to manage and no battery drain. Requires the CAMERA permission (requested by the UI).
 */
class CameraPlugin(private val ctx: Context) : JarvisPlugin {
    override val id = "camera"
    override val displayName = "Camera (JARVIS can see)"

    @Volatile var frontFacing: Boolean = true
    @Volatile var latestFrameB64: String? = null
    @Volatile var enabled: Boolean = false

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val io = Executors.newSingleThreadExecutor()
    private val cacheFile = File(ctx.cacheDir, "jarvis_cam.jpg")

    // CameraX needs a LifecycleOwner; the plugin owns a minimal one held in RESUMED while
    // the camera is bound, so it works without a visible Activity.
    private val lifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init { registry.currentState = Lifecycle.State.RESUMED }
        fun teardown() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private fun selector() =
        if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    /** Bind the camera once; subsequent captures reuse it. Runs on a background thread. */
    private fun ensureCamera(onReady: (Boolean) -> Unit) {
        if (provider != null && imageCapture != null) { onReady(true); return }
        io.execute {
            try {
                val future = ProcessCameraProvider.getInstance(ctx)
                val prov = future.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                ContextCompat.getMainExecutor(ctx).execute {
                    try {
                        prov.unbindAll()
                        prov.bindToLifecycle(lifecycleOwner, selector(), capture)
                        provider = prov
                        imageCapture = capture
                        enabled = true
                        onReady(true)
                    } catch (e: Exception) { onReady(false) }
                }
            } catch (e: Exception) { onReady(false) }
        }
    }

    /** Grab one frame; [onFrame] receives a downscaled JPEG as base64, or null on failure. */
    fun captureNow(onFrame: (String?) -> Unit) {
        ensureCamera { ok ->
            if (!ok) { onFrame(null); return@ensureCamera }
            val capture = imageCapture ?: run { onFrame(null); return@ensureCamera }
            val options = ImageCapture.OutputFileOptions.Builder(cacheFile).build()
            capture.takePicture(options, ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        io.execute {
                            val b64 = decodeToB64(cacheFile)
                            latestFrameB64 = b64
                            ContextCompat.getMainExecutor(ctx).execute { onFrame(b64) }
                        }
                    }
                    override fun onError(exc: ImageCaptureException) { onFrame(null) }
                })
        }
    }

    /** Decode + downscale a JPEG file to base64 (keeps the payload small for the model). */
    private fun decodeToB64(file: File): String? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val maxSide = 768
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val rotated = if (frontFacing) mirror(bmp) else bmp   // selfie preview feels natural
        val out = ByteArrayOutputStream()
        return try {
            rotated.compress(Bitmap.CompressFormat.JPEG, 82, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { out.close() }
            if (rotated !== bmp) runCatching { rotated.recycle() }
            runCatching { bmp.recycle() }
        }
    }

    private fun mirror(src: Bitmap): Bitmap {
        val m = Matrix().apply { postScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    override fun onCommand(command: String): Boolean {
        val c = command.trim().lowercase()
        if (c.contains("camera") || c.contains("look at") || c.contains("what do you see")) {
            captureNow { }   // refresh latestFrameB64; live-ask will pick it up
            return true
        }
        return false
    }

    override fun statusLine(): String? = if (enabled) "📷 Camera ready (${if (frontFacing) "front" else "back"})" else null

    override fun onDestroy() {
        runCatching { provider?.unbindAll() }
        runCatching { lifecycleOwner.teardown() }
        provider = null; imageCapture = null; enabled = false
        io.shutdown()
    }
}
