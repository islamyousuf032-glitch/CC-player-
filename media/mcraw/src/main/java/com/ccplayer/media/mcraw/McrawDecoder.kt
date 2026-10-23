package com.ccplayer.media.mcraw

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

data class McrawInfo(
    val frameCount: Int,
    val timestampSpanUs: Long,
    val width: Int,
    val height: Int,
    val audioSampleRateHz: Int,
    val audioChannels: Int,
    val hasGyro: Boolean,
    val hasAccelerometer: Boolean,
    val sensorArrangement: String,
)

/**
 * Native MotionCam decoder access.
 *
 * The preview is deliberately labelled draft: it performs fast 2x2 CFA development, white balance
 * and gamma, but not the future camera-specific color-managed GPU render pipeline.
 */
class McrawDecoder(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver

    suspend fun probe(uri: Uri): McrawInfo = withContext(Dispatchers.IO) {
        val json = withFileDescriptor(uri) { nativeProbe(it) }
        val value = JSONObject(json)
        McrawInfo(
            frameCount = value.getInt("frameCount"),
            timestampSpanUs = value.getLong("timestampSpanUs"),
            width = value.getInt("width"),
            height = value.getInt("height"),
            audioSampleRateHz = value.optInt("audioSampleRateHz"),
            audioChannels = value.optInt("audioChannels"),
            hasGyro = value.optBoolean("hasGyro"),
            hasAccelerometer = value.optBoolean("hasAccelerometer"),
            sensorArrangement = value.optString("sensorArrangement"),
        )
    }

    suspend fun renderDraftPreview(uri: Uri, frameIndex: Int, maxDimension: Int = 960): Bitmap =
        withContext(Dispatchers.Default) {
            require(frameIndex >= 0) { "Frame index cannot be negative" }
            val values = withFileDescriptor(uri) {
                nativeRenderDraftPreview(it, frameIndex, maxDimension)
            }
            require(values.size >= 3) { "Native decoder returned no preview" }
            val width = values[0]
            val height = values[1]
            require(width > 0 && height > 0 && values.size == 2 + width * height) {
                "Native decoder returned an invalid preview buffer"
            }
            Bitmap.createBitmap(
                values,
                /* offset = */ 2,
                /* stride = */ width,
                width,
                height,
                Bitmap.Config.ARGB_8888,
            )
        }

    private inline fun <T> withFileDescriptor(uri: Uri, block: (Int) -> T): T {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Could not open MCRAW file")
        return descriptor.use { block(it.fd) }
    }

    private external fun nativeProbe(fd: Int): String
    private external fun nativeRenderDraftPreview(fd: Int, frameIndex: Int, maxDimension: Int): IntArray

    companion object {
        init {
            System.loadLibrary("ccplayer_mcraw")
        }
    }
}
