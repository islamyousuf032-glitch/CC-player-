package com.ccplayer.media.android.export

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.ccplayer.core.media.color.ColorGrade
import com.ccplayer.core.media.export.VideoCodec
import com.ccplayer.media.android.color.ColorGradeEffects
import java.io.File

@UnstableApi
class Media3ExportEngine(context: Context) {
    interface Listener {
        fun onCompleted(outputFile: File)
        fun onError(message: String)
        fun onFallback(message: String)
    }

    private val appContext = context.applicationContext
    private var transformer: Transformer? = null
    private var outputFile: File? = null

    val isRunning: Boolean get() = transformer != null

    fun start(
        input: Uri,
        destination: File,
        codec: VideoCodec,
        grade: ColorGrade,
        listener: Listener,
    ) {
        check(transformer == null) { "An export is already running" }
        require(codec in listOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.APV)) {
            "${codec.displayName} needs the native export backend"
        }
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            listener.onError("Could not replace temporary export file")
            return
        }

        outputFile = destination
        runCatching {
            val exportListener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val completed = destination
                    clearOperation()
                    listener.onCompleted(completed)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    clearOperation()
                    listener.onError(exportException.message ?: "Video export failed")
                }

                override fun onFallbackApplied(
                    composition: Composition,
                    originalTransformationRequest: TransformationRequest,
                    fallbackTransformationRequest: TransformationRequest,
                ) {
                    listener.onFallback(
                        "Device fallback: ${fallbackTransformationRequest.videoMimeType ?: "resolution changed"}",
                    )
                }
            }

            Transformer.Builder(appContext)
                .setVideoMimeType(requireNotNull(codec.mimeType))
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                // Never silently substitute another codec or resolution for a professional export.
                .setEncoderFactory(DefaultEncoderFactory.Builder(appContext).setEnableFallback(false).build())
                .addListener(exportListener)
                .build()
                .also { created ->
                    transformer = created
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
                        .setEffects(Effects(emptyList(), ColorGradeEffects.create(grade)))
                        .build()
                    created.start(item, destination.absolutePath)
                }
        }.onFailure { error ->
            clearOperation()
            listener.onError(error.message ?: "Could not start export")
        }
    }

    /** Returns a percentage, or null while Transformer cannot estimate progress. */
    fun progress(): Int? {
        val current = transformer ?: return null
        val holder = ProgressHolder()
        return if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            holder.progress.coerceIn(0, 100)
        } else {
            null
        }
    }

    fun cancel() {
        transformer?.cancel()
        clearOperation()
        outputFile?.delete()
    }

    private fun clearOperation() {
        transformer = null
        outputFile = null
    }
}
