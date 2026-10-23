package com.ccplayer.core.media.format

import java.util.Locale

enum class InputBackend {
    PLATFORM_FIRST,
    MCRAW_NATIVE,
    FFMPEG_NATIVE,
    IMAGE_SEQUENCE,
    VENDOR_SDK,
}

data class InputFormatRoute(
    val extension: String,
    val backend: InputBackend,
    val currentlyPlayable: Boolean,
    val detail: String,
)

/**
 * Routes only formats that can be identified without bitstream probing. MOV/MP4 are containers,
 * not codecs, so their final route must be confirmed after demuxing.
 */
object InputFormatRouter {
    private val platformContainers = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "3gp", "avi")
    private val ffmpegContainers = setOf("apv", "mxf", "vob", "mpg", "mpeg", "wmv", "flv", "mts", "m2ts")
    private val sequences = setOf("dng", "exr", "dpx")
    private val vendorRaw = setOf("braw", "r3d", "crm", "ari", "arx")

    fun route(fileName: String): InputFormatRoute {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension == "mcraw" -> InputFormatRoute(
                extension,
                InputBackend.MCRAW_NATIVE,
                currentlyPlayable = false,
                detail = "Native MCRAW metadata and draft frame preview are available; real-time playback is a separate renderer stage.",
            )
            extension in platformContainers -> InputFormatRoute(
                extension,
                InputBackend.PLATFORM_FIRST,
                currentlyPlayable = true,
                detail = "Try Media3/MediaCodec first; an unsupported embedded codec needs the native FFmpeg fallback.",
            )
            extension in sequences -> InputFormatRoute(
                extension,
                InputBackend.IMAGE_SEQUENCE,
                currentlyPlayable = false,
                detail = "This file needs an image-sequence reader with explicit frame rate and color interpretation.",
            )
            extension in vendorRaw -> InputFormatRoute(
                extension,
                InputBackend.VENDOR_SDK,
                currentlyPlayable = false,
                detail = "This camera RAW family requires a separately licensed vendor SDK or a compatible decoder.",
            )
            extension in ffmpegContainers -> InputFormatRoute(
                extension,
                InputBackend.FFMPEG_NATIVE,
                currentlyPlayable = false,
                detail = "This route requires the planned native demux/decoder backend.",
            )
            else -> InputFormatRoute(
                extension,
                InputBackend.PLATFORM_FIRST,
                currentlyPlayable = true,
                detail = "The container is unknown; Media3 will probe it instead of trusting the extension.",
            )
        }
    }
}
