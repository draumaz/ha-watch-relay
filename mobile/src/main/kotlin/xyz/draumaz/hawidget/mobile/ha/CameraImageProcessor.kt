package xyz.draumaz.hawidget.mobile.ha

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class ProcessedCameraFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
)

object CameraImageProcessor {

    /**
     * Decodes raw image bytes from Home Assistant `/api/camera_proxy/<entity_id>`,
     * scales proportionally to at most [maxWidthPx] on the longest edge, and encodes
     * as JPEG suitable for fast Bluetooth/Wearable Data Layer transfer.
     */
    fun processForWatch(
        rawBytes: ByteArray,
        maxWidthPx: Int,
        jpegQuality: Int,
    ): ProcessedCameraFrame {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) {
            return ProcessedCameraFrame(rawBytes, 0, 0)
        }

        var sampleSize = 1
        while ((srcW / (sampleSize * 2)) >= maxWidthPx && (srcH / (sampleSize * 2)) >= maxWidthPx) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: return ProcessedCameraFrame(rawBytes, 0, 0)

        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > maxWidthPx) {
            val ratio = maxWidthPx.toFloat() / longest.toFloat()
            val targetW = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
            val targetH = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(30, 95), out)
        val resultW = scaled.width
        val resultH = scaled.height
        scaled.recycle()

        return ProcessedCameraFrame(
            jpegBytes = out.toByteArray(),
            width = resultW,
            height = resultH,
        )
    }
}
