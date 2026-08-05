package com.dangtuan.btranslate.translation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureController(
    context: Context,
    resultCode: Int,
    resultData: Intent,
    private var width: Int,
    private var height: Int,
    private val density: Int,
    private val onDisconnected: () -> Unit
) {
    private val projection: MediaProjection = context.getSystemService(MediaProjectionManager::class.java)
        .getMediaProjection(resultCode, resultData)
    private val thread = HandlerThread("b-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader = makeReader(width, height)
    private val waiting = AtomicReference<CompletableDeferred<Bitmap>?>(null)
    private val connected = AtomicBoolean(true)
    private val display: VirtualDisplay

    init {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (connected.getAndSet(false)) {
                    waiting.getAndSet(null)?.cancel()
                    onDisconnected()
                }
            }
            override fun onCapturedContentResize(capturedWidth: Int, capturedHeight: Int) {
                resize(capturedWidth, capturedHeight)
            }
        }, handler)
        display = projection.createVirtualDisplay(
            "BTranslateCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    suspend fun capture(): Bitmap {
        check(connected.get()) { "Kết nối chụp màn hình đã ngắt" }
        val deferred = CompletableDeferred<Bitmap>()
        waiting.getAndSet(deferred)?.cancel()
        return deferred.await()
    }

    @Synchronized
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        val old = reader
        val replacement = makeReader(newWidth, newHeight)
        display.resize(newWidth, newHeight, density)
        display.surface = replacement.surface
        reader = replacement
        width = newWidth
        height = newHeight
        old.close()
    }

    fun close() {
        connected.set(false)
        waiting.getAndSet(null)?.cancel()
        display.release()
        reader.close()
        projection.stop()
        thread.quitSafely()
    }

    private fun makeReader(w: Int, h: Int): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { imageReader ->
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val deferred = waiting.getAndSet(null) ?: return@setOnImageAvailableListener
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (cropped !== padded) padded.recycle()
                deferred.complete(cropped)
            } catch (error: Exception) {
                waiting.getAndSet(null)?.completeExceptionally(error)
            } finally {
                image.close()
            }
        }, handler)
    }
}
