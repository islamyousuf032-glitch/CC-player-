package com.ccplayer.media.ffmpeg

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Decodes one SAF frame through FFmpeg, including formats BitmapFactory cannot read (EXR/DPX/etc.). */
class FFmpegImageDecoder(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun decode(uri: Uri, maxDimension: Int = 960): Bitmap = withContext(Dispatchers.Default) {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Could not open sequence frame")
        descriptor.use {
            val values = nativeDecode(it.fd, maxDimension)
            require(values.size >= 3) { "Native image decoder returned no frame" }
            val width = values[0]
            val height = values[1]
            require(width > 0 && height > 0 && values.size == 2 + width * height) {
                "Native image decoder returned an invalid buffer"
            }
            Bitmap.createBitmap(values, 2, width, width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private external fun nativeDecode(fd: Int, maxDimension: Int): IntArray

    private companion object {
        init { System.loadLibrary("ccplayer_ffmpeg") }
    }
}
