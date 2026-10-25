package com.ccplayer.media.mcraw

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException

data class McrawAudioChunk(val timestampUs: Long, val samples: ShortArray)

enum class McrawRenderQuality(val nativeValue: Int) {
    DRAFT(0),
    BILINEAR_CPU(1),
}

/** A persistent decoder: frame scrubbing and sequential audio no longer reopen/reindex the recording. */
class McrawSession(context: Context, uri: Uri) : Closeable {
    private val descriptor: ParcelFileDescriptor = context.applicationContext.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Could not open MCRAW file")
    private var handle: Long = try {
        nativeOpen(descriptor.fd)
    } catch (error: Throwable) {
        descriptor.close()
        throw error
    }

    @get:Synchronized
    val frameCount: Int get() = nativeFrameCount(handle())

    @Synchronized
    fun frameTimestampUs(frameIndex: Int): Long = nativeFrameTimestampUs(handle(), frameIndex)

    @Synchronized
    fun renderDraft(
        frameIndex: Int,
        maxDimension: Int = 640,
        quality: McrawRenderQuality = McrawRenderQuality.DRAFT,
    ): Bitmap {
        val values = nativeRender(handle(), frameIndex, maxDimension, quality.nativeValue)
        require(values.size >= 3) { "Native decoder returned no preview" }
        val width = values[0]
        val height = values[1]
        require(width > 0 && height > 0 && values.size == 2 + width * height) {
            "Native decoder returned an invalid preview buffer"
        }
        return Bitmap.createBitmap(values, 2, width, width, height, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    fun nextAudioChunk(): McrawAudioChunk? {
        if (handle == 0L) return null
        val info = LongArray(1)
        val samples = nativeNextAudioChunk(handle, info) ?: return null
        return McrawAudioChunk(info[0], samples)
    }

    private fun handle(): Long = handle.takeIf { it != 0L } ?: throw IOException("MCRAW session is closed")

    @Synchronized
    override fun close() {
        val pointer = handle
        handle = 0
        if (pointer != 0L) nativeClose(pointer)
        descriptor.close()
    }

    private external fun nativeOpen(fd: Int): Long
    private external fun nativeFrameCount(pointer: Long): Int
    private external fun nativeFrameTimestampUs(pointer: Long, frameIndex: Int): Long
    private external fun nativeRender(pointer: Long, frameIndex: Int, maxDimension: Int, quality: Int): IntArray
    private external fun nativeNextAudioChunk(pointer: Long, timestampUs: LongArray): ShortArray?
    private external fun nativeClose(pointer: Long)

    private companion object {
        init { System.loadLibrary("ccplayer_mcraw") }
    }
}
