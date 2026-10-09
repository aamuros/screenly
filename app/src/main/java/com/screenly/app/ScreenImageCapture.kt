package com.screenly.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private class ImageOutput : ByteArrayOutputStream() {
    override fun close() { buf.fill(0); reset(); super.close() }
}

internal enum class CaptureFailure { PRIVACY, ANDROID_DENIED, STALE, DECODE, TIMEOUT }
internal sealed interface ScreenCapture {
    data class Image(val frame: ScreenImage) : ScreenCapture
    data class Failed(val reason: CaptureFailure) : ScreenCapture
}

/** Only accessibility bounds are used for highlights; this mapping documents the image crop. */
internal class ScreenImage(
    val key: SnapshotKey, val bytes: ByteArray,
    val left: Int, val top: Int, val sourceWidth: Int, val sourceHeight: Int,
    val imageWidth: Int, val imageHeight: Int
) : AutoCloseable {
    override fun close() { bytes.fill(0) }
}

internal class ScreenImageCapture(private val service: AccessibilityService) {
    suspend fun capture(snapshot: GuidanceSnapshot, displayWidth: Int, displayHeight: Int): ScreenCapture {
        val observation = snapshot.observation
        if (!observation.imageAllowed) return ScreenCapture.Failed(CaptureFailure.PRIVACY)
        val crop = Rect(observation.windowLeft, observation.windowTop, observation.windowRight, observation.windowBottom)
        if (crop.isEmpty || crop.left < 0 || crop.top < 0 || crop.right > displayWidth || crop.bottom > displayHeight) {
            return ScreenCapture.Failed(CaptureFailure.DECODE)
        }
        return withTimeoutOrNull(4000) {
            suspendCancellableCoroutine { continuation ->
                try {
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.IO.asExecutor(),
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onFailure(errorCode: Int) {
                                if (continuation.isActive) continuation.resume(ScreenCapture.Failed(CaptureFailure.ANDROID_DENIED))
                            }
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                val buffer = result.hardwareBuffer
                                var hardware: Bitmap? = null
                                var software: Bitmap? = null
                                var cropped: Bitmap? = null
                                var scaled: Bitmap? = null
                                var frame: ScreenImage? = null
                                try {
                                    if (!continuation.isActive) return
                                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    val original = checkNotNull(hardware)
                                    // Android returns display-oriented pixels. A dimension mismatch is rejected,
                                    // never rotated or stretched using guessed orientation metadata.
                                    check(original.width == displayWidth && original.height == displayHeight)
                                    software = original.copy(Bitmap.Config.ARGB_8888, false)
                                    cropped = Bitmap.createBitmap(checkNotNull(software), crop.left, crop.top, crop.width(), crop.height())
                                    val ratio = minOf(1.0, 768.0 / maxOf(crop.width(), crop.height()))
                                    scaled = Bitmap.createScaledBitmap(cropped, (crop.width() * ratio).roundToInt().coerceAtLeast(1),
                                        (crop.height() * ratio).roundToInt().coerceAtLeast(1), true)
                                    val encoded = ImageOutput().use { stream ->
                                        check(scaled.compress(Bitmap.CompressFormat.PNG, 100, stream))
                                        stream.toByteArray()
                                    }
                                    if (encoded.size > 2_000_000) { encoded.fill(0); error("Image exceeds budget") }
                                    frame = ScreenImage(snapshot.key, encoded, crop.left, crop.top, crop.width(), crop.height(), scaled.width, scaled.height)
                                    if (continuation.isActive) continuation.resume(ScreenCapture.Image(frame)) { _, _, _ -> frame.close() }
                                    else frame.close()
                                } catch (_: Exception) {
                                    frame?.close()
                                    if (continuation.isActive) continuation.resume(ScreenCapture.Failed(CaptureFailure.DECODE))
                                } finally {
                                    listOfNotNull(scaled, cropped, software, hardware).distinct().forEach { it.recycle() }
                                    buffer.close()
                                }
                            }
                        })
                } catch (_: RuntimeException) {
                    if (continuation.isActive) continuation.resume(ScreenCapture.Failed(CaptureFailure.ANDROID_DENIED))
                }
            }
        } ?: ScreenCapture.Failed(CaptureFailure.TIMEOUT)
    }
}
