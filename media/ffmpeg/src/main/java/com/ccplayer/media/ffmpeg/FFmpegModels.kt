package com.ccplayer.media.ffmpeg

import android.net.Uri
import com.ccplayer.core.media.color.ColorGrade
import java.io.File

data class NativeStreamInfo(
    val index: Int,
    val type: String,
    val codec: String,
    val codecLongName: String,
    val profile: String,
    val profileId: Int,
    val pixelFormat: String,
    val sampleFormat: String,
    val language: String,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Long,
    val durationUs: Long,
    val startTimeUs: Long,
    val frameRateNum: Int,
    val frameRateDen: Int,
    val timeBaseNum: Int,
    val timeBaseDen: Int,
    val sampleAspectRatioNum: Int,
    val sampleAspectRatioDen: Int,
    val colorPrimaries: Int,
    val colorTransfer: Int,
    val colorSpace: Int,
    val colorRange: Int,
    val codecTag: Long,
    val frameCount: Long,
    val rotationDegrees: Double,
    val attachedPicture: Boolean,
    val defaultDisposition: Boolean,
) {
    val isHdr: Boolean
        get() = colorPrimaries == BT2020_PRIMARIES || colorTransfer == PQ_TRANSFER || colorTransfer == HLG_TRANSFER

    companion object {
        private const val BT2020_PRIMARIES = 9
        private const val PQ_TRANSFER = 16
        private const val HLG_TRANSFER = 18
    }
}

data class NativeMediaInfo(
    val format: String,
    val formatLongName: String,
    val durationUs: Long,
    val bitRate: Long,
    val startTimeUs: Long,
    val streams: List<NativeStreamInfo>,
) {
    val primaryVideo: NativeStreamInfo?
        get() = streams.firstOrNull { it.type == "video" && !it.attachedPicture && it.defaultDisposition }
            ?: streams.firstOrNull { it.type == "video" && !it.attachedPicture }
    val primaryAudio: NativeStreamInfo?
        get() = streams.firstOrNull { it.type == "audio" && it.defaultDisposition }
            ?: streams.firstOrNull { it.type == "audio" }
}

enum class NativeAudioCodec(val nativeName: String, val displayName: String) {
    COPY("COPY", "Copy source (no trim)"),
    NONE("NONE", "No audio"),
    PCM_S16LE("pcm_s16le", "PCM 16-bit"),
    PCM_S24LE("pcm_s24le", "PCM 24-bit"),
    AAC("aac", "AAC"),
    FLAC("flac", "FLAC"),
}

data class NativeExportRequest(
    val input: Uri,
    val destination: File,
    val codec: String,
    val profile: String,
    val pixelFormat: String,
    val audioCodec: NativeAudioCodec = NativeAudioCodec.PCM_S24LE,
    val width: Int = 0,
    val height: Int = 0,
    val frameRateNum: Int = 0,
    val frameRateDen: Int = 0,
    val audioBitRate: Int = 256_000,
    val audioSampleRate: Int = 0,
    val videoQuality: Int = -1,
    val threadCount: Int = 0,
    val trimStartUs: Long = 0,
    val trimEndUs: Long = -1,
    val grade: ColorGrade = ColorGrade.IDENTITY,
    val useHdr: Boolean? = null,
)

data class NativeExportResult(
    val cancelled: Boolean,
    val verified: Boolean,
    val codec: String,
    val profile: String,
    val pixelFormat: String,
    val audioCodec: String,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val frameRateNum: Int,
    val frameRateDen: Int,
    val durationUs: Long,
    val fileSizeBytes: Long,
    val encodedFrames: Long,
)

data class FFmpegRuntimeInfo(
    val version: String,
    val configuration: String,
    val license: String,
    val encoders: Map<String, Boolean>,
    val decoders: Map<String, Boolean>,
)
