package com.ccplayer.media.ffmpeg

import java.io.IOException

class FFmpegException(message: String) : IOException(message)

internal object FFmpegNative {
    init {
        // Explicit dependency order: FFmpeg remains dynamically replaceable and OpenAPV is
        // statically linked into libavcodec under its BSD-3-Clause license.
        System.loadLibrary("avutil")
        System.loadLibrary("swresample")
        System.loadLibrary("swscale")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("ccplayer_ffmpeg")
    }

    external fun nativeProbe(inputPath: String): String
    external fun nativeRuntimeInfo(): String

    @Suppress("LongParameterList")
    external fun nativeExport(
        inputPath: String,
        outputPath: String,
        codec: String,
        profile: String,
        pixelFormat: String,
        audioCodec: String,
        width: Int,
        height: Int,
        frameRateNum: Int,
        frameRateDen: Int,
        audioBitRate: Int,
        audioSampleRate: Int,
        videoQuality: Int,
        threadCount: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        useHdr: Boolean,
        grade: FloatArray,
        listener: Any,
    ): String
}
