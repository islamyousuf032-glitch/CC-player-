package com.ccplayer.core.media.export

enum class VideoCodec(val displayName: String, val mimeType: String?) {
    H264("H.264 / AVC", "video/avc"),
    HEVC("H.265 / HEVC", "video/hevc"),
    APV("APV", "video/apv"),
    PRORES_422_PROXY("ProRes 422 Proxy", null),
    PRORES_422_LT("ProRes 422 LT", null),
    PRORES_422("ProRes 422", null),
    PRORES_422_HQ("ProRes 422 HQ", null),
    PRORES_4444("ProRes 4444", null),
    PRORES_4444_XQ("ProRes 4444 XQ", null),
    DNXHR("DNxHR", null),
    FFV1("FFV1", null),
    PNG("PNG", null),
    TIFF("TIFF", null),
    DPX("DPX", null),
    OPEN_EXR("OpenEXR", null),
}

enum class MediaContainer(val extension: String, val mimeType: String, val isImageSequence: Boolean = false) {
    MP4("mp4", "video/mp4"),
    MOV("mov", "video/quicktime"),
    MATROSKA("mkv", "video/x-matroska"),
    PNG_SEQUENCE("png", "image/png", true),
    TIFF_SEQUENCE("tif", "image/tiff", true),
    DPX_SEQUENCE("dpx", "image/x-dpx", true),
    EXR_SEQUENCE("exr", "image/x-exr", true),
}

enum class ExportBackend { MEDIA3, FFMPEG, OPEN_APV }
enum class ImplementationStatus { IMPLEMENTED, PLANNED }
enum class ExportAudioCodec(val displayName: String) {
    AAC("AAC 256 kb/s"),
    PCM16("PCM 16-bit"),
    PCM24("PCM 24-bit"),
    FLAC("FLAC"),
    COPY("Copy source"),
    NONE("No audio"),
}

data class ExportProfile(
    val id: String,
    val codec: VideoCodec,
    val container: MediaContainer,
    val backend: ExportBackend,
    val status: ImplementationStatus,
    val title: String = codec.displayName,
    val encoderName: String? = null,
    val encoderProfile: String? = null,
    val pixelFormat: String? = null,
    val audio: ExportAudioCodec = ExportAudioCodec.AAC,
    val defaultQuality: Int = -1,
) {
    init { require(id.isNotBlank()) { "Profile id cannot be blank" } }
    val usesNativeBackend: Boolean get() = backend != ExportBackend.MEDIA3
}

object ExportProfiles {
    val H264 = ExportProfile("h264-mp4", VideoCodec.H264, MediaContainer.MP4, ExportBackend.MEDIA3, ImplementationStatus.IMPLEMENTED)
    val HEVC = ExportProfile("hevc-mp4", VideoCodec.HEVC, MediaContainer.MP4, ExportBackend.MEDIA3, ImplementationStatus.IMPLEMENTED)
    val APV = ExportProfile("apv-hardware-mp4", VideoCodec.APV, MediaContainer.MP4, ExportBackend.MEDIA3, ImplementationStatus.IMPLEMENTED, title = "APV • device hardware")

    val PRORES_PROXY = native("prores-proxy-mov", VideoCodec.PRORES_422_PROXY, "prores_ks", "proxy", "yuv422p10", ExportAudioCodec.PCM24)
    val PRORES_LT = native("prores-lt-mov", VideoCodec.PRORES_422_LT, "prores_ks", "lt", "yuv422p10", ExportAudioCodec.PCM24)
    val PRORES_422 = native("prores-422-mov", VideoCodec.PRORES_422, "prores_ks", "standard", "yuv422p10", ExportAudioCodec.PCM24)
    val PRORES = native("prores-422-hq-mov", VideoCodec.PRORES_422_HQ, "prores_ks", "hq", "yuv422p10", ExportAudioCodec.PCM24)
    val PRORES_4444 = native("prores-4444-mov", VideoCodec.PRORES_4444, "prores_ks", "4444", "yuv444p10", ExportAudioCodec.PCM24)
    val PRORES_4444_ALPHA = native("prores-4444-alpha-mov", VideoCodec.PRORES_4444, "prores_ks", "4444", "yuva444p10", ExportAudioCodec.PCM24, title = "ProRes 4444 • alpha")
    val PRORES_4444_XQ = native("prores-4444xq-mov", VideoCodec.PRORES_4444_XQ, "prores_ks", "4444xq", "yuv444p10", ExportAudioCodec.PCM24)
    val PRORES_4444_XQ_ALPHA = native("prores-4444xq-alpha-mov", VideoCodec.PRORES_4444_XQ, "prores_ks", "4444xq", "yuva444p10", ExportAudioCodec.PCM24, title = "ProRes 4444 XQ • alpha")

    val DNXHR_LB = native("dnxhr-lb-mov", VideoCodec.DNXHR, "dnxhd", "dnxhr_lb", "yuv422p", ExportAudioCodec.PCM24, title = "DNxHR LB • 8-bit 4:2:2")
    val DNXHR_SQ = native("dnxhr-sq-mov", VideoCodec.DNXHR, "dnxhd", "dnxhr_sq", "yuv422p", ExportAudioCodec.PCM24, title = "DNxHR SQ • 8-bit 4:2:2")
    val DNXHR_HQ = native("dnxhr-hq-mov", VideoCodec.DNXHR, "dnxhd", "dnxhr_hq", "yuv422p", ExportAudioCodec.PCM24, title = "DNxHR HQ • 8-bit 4:2:2")
    val DNXHR_HQX = native("dnxhr-hqx-mov", VideoCodec.DNXHR, "dnxhd", "dnxhr_hqx", "yuv422p10", ExportAudioCodec.PCM24, title = "DNxHR HQX • 10-bit 4:2:2")
    val DNXHR_444 = native("dnxhr-444-mov", VideoCodec.DNXHR, "dnxhd", "dnxhr_444", "yuv444p10", ExportAudioCodec.PCM24, title = "DNxHR 444 • 10-bit")

    val FFV1 = ExportProfile("ffv1-yuv444p16-mkv", VideoCodec.FFV1, MediaContainer.MATROSKA, ExportBackend.FFMPEG, ImplementationStatus.IMPLEMENTED,
        title = "FFV1 • 16-bit 4:4:4 + FLAC", encoderName = "ffv1", encoderProfile = "default", pixelFormat = "yuv444p16", audio = ExportAudioCodec.FLAC)
    val FFV1_ALPHA = ExportProfile("ffv1-yuva444p16-mkv", VideoCodec.FFV1, MediaContainer.MATROSKA, ExportBackend.FFMPEG, ImplementationStatus.IMPLEMENTED,
        title = "FFV1 • 16-bit 4:4:4:4 + FLAC", encoderName = "ffv1", encoderProfile = "default", pixelFormat = "yuva444p16", audio = ExportAudioCodec.FLAC)

    val PNG_16 = sequence("png-16-sequence", VideoCodec.PNG, MediaContainer.PNG_SEQUENCE, "png", "rgb48be", "PNG sequence • 16-bit RGB")
    val PNG_16_ALPHA = sequence("png-16-alpha-sequence", VideoCodec.PNG, MediaContainer.PNG_SEQUENCE, "png", "rgba64be", "PNG sequence • 16-bit RGBA")
    val TIFF_16 = sequence("tiff-16-sequence", VideoCodec.TIFF, MediaContainer.TIFF_SEQUENCE, "tiff", "rgb48le", "TIFF sequence • 16-bit RGB")
    val TIFF_16_ALPHA = sequence("tiff-16-alpha-sequence", VideoCodec.TIFF, MediaContainer.TIFF_SEQUENCE, "tiff", "rgba64le", "TIFF sequence • 16-bit RGBA")
    val DPX_16 = sequence("dpx-16-sequence", VideoCodec.DPX, MediaContainer.DPX_SEQUENCE, "dpx", "rgb48be", "DPX sequence • 16-bit RGB")
    val DPX_16_ALPHA = sequence("dpx-16-alpha-sequence", VideoCodec.DPX, MediaContainer.DPX_SEQUENCE, "dpx", "rgba64be", "DPX sequence • 16-bit RGBA")
    val EXR_FLOAT = sequence("exr-float-sequence", VideoCodec.OPEN_EXR, MediaContainer.EXR_SEQUENCE, "exr", "gbrpf32le", "OpenEXR sequence • 32-bit float RGB")
    val EXR_FLOAT_ALPHA = sequence("exr-float-alpha-sequence", VideoCodec.OPEN_EXR, MediaContainer.EXR_SEQUENCE, "exr", "gbrapf32le", "OpenEXR sequence • 32-bit float RGBA")

    val APV_SOFTWARE_400_10 = apvSoftware("apv-software-400-10-mp4", "400_10", "gray10", "APV • software monochrome 10-bit")
    val APV_SOFTWARE = apvSoftware("apv-software-422-10-mp4", "422_10", "yuv422p10", "APV • software 4:2:2 10-bit")
    val APV_SOFTWARE_422_12 = apvSoftware("apv-software-422-12-mp4", "422_12", "yuv422p12", "APV • software 4:2:2 12-bit")
    val APV_SOFTWARE_444_10 = apvSoftware("apv-software-444-10-mp4", "444_10", "yuv444p10", "APV • software 4:4:4 10-bit")
    val APV_SOFTWARE_444_12 = apvSoftware("apv-software-444-12-mp4", "444_12", "yuv444p12", "APV • software 4:4:4 12-bit")
    val APV_SOFTWARE_4444_10 = apvSoftware("apv-software-4444-10-mp4", "4444_10", "yuva444p10", "APV • software 4:4:4:4 10-bit")
    val APV_SOFTWARE_4444_12 = apvSoftware("apv-software-4444-12-mp4", "4444_12", "yuva444p12", "APV • software 4:4:4:4 12-bit")

    val current = listOf(
        H264, HEVC, APV,
        PRORES_PROXY, PRORES_LT, PRORES_422, PRORES,
        PRORES_4444, PRORES_4444_ALPHA, PRORES_4444_XQ, PRORES_4444_XQ_ALPHA,
        DNXHR_LB, DNXHR_SQ, DNXHR_HQ, DNXHR_HQX, DNXHR_444,
        FFV1, FFV1_ALPHA,
        PNG_16, PNG_16_ALPHA, TIFF_16, TIFF_16_ALPHA,
        DPX_16, DPX_16_ALPHA, EXR_FLOAT, EXR_FLOAT_ALPHA,
        APV_SOFTWARE_400_10, APV_SOFTWARE, APV_SOFTWARE_422_12,
        APV_SOFTWARE_444_10, APV_SOFTWARE_444_12,
        APV_SOFTWARE_4444_10, APV_SOFTWARE_4444_12,
    )
    val all = current

    fun byId(id: String): ExportProfile = all.firstOrNull { it.id == id } ?: H264

    fun validate(profile: ExportProfile): List<String> = buildList {
        when (profile.codec) {
            VideoCodec.H264, VideoCodec.HEVC, VideoCodec.APV -> if (profile.container != MediaContainer.MP4) add("${profile.codec.displayName} targets MP4")
            VideoCodec.PRORES_422_PROXY, VideoCodec.PRORES_422_LT, VideoCodec.PRORES_422,
            VideoCodec.PRORES_422_HQ, VideoCodec.PRORES_4444, VideoCodec.PRORES_4444_XQ,
            VideoCodec.DNXHR -> if (profile.container != MediaContainer.MOV) add("${profile.title} requires MOV")
            VideoCodec.FFV1 -> if (profile.container != MediaContainer.MATROSKA) add("FFV1 requires Matroska")
            VideoCodec.PNG -> if (profile.container != MediaContainer.PNG_SEQUENCE) add("PNG requires an image-sequence destination")
            VideoCodec.TIFF -> if (profile.container != MediaContainer.TIFF_SEQUENCE) add("TIFF requires an image-sequence destination")
            VideoCodec.DPX -> if (profile.container != MediaContainer.DPX_SEQUENCE) add("DPX requires an image-sequence destination")
            VideoCodec.OPEN_EXR -> if (profile.container != MediaContainer.EXR_SEQUENCE) add("OpenEXR requires an image-sequence destination")
        }
        if (profile.container.isImageSequence && profile.audio != ExportAudioCodec.NONE) add("Image sequences cannot carry an audio stream")
        if (profile.backend == ExportBackend.MEDIA3 && profile.codec.mimeType == null) add("Media3 cannot encode ${profile.title}; use native")
        if (profile.usesNativeBackend && (profile.encoderName.isNullOrBlank() || profile.pixelFormat.isNullOrBlank())) add("Native profile needs exact encoder and pixel format")
    }

    private fun native(
        id: String,
        codec: VideoCodec,
        encoder: String,
        encoderProfile: String,
        pixelFormat: String,
        audio: ExportAudioCodec,
        title: String = codec.displayName,
    ) = ExportProfile(id, codec, MediaContainer.MOV, ExportBackend.FFMPEG, ImplementationStatus.IMPLEMENTED,
        title = title, encoderName = encoder, encoderProfile = encoderProfile, pixelFormat = pixelFormat, audio = audio)

    private fun sequence(
        id: String,
        codec: VideoCodec,
        container: MediaContainer,
        encoder: String,
        pixelFormat: String,
        title: String,
    ) = ExportProfile(id, codec, container, ExportBackend.FFMPEG, ImplementationStatus.IMPLEMENTED,
        title = title, encoderName = encoder, encoderProfile = "default", pixelFormat = pixelFormat, audio = ExportAudioCodec.NONE)

    private fun apvSoftware(id: String, profile: String, pixelFormat: String, title: String) = ExportProfile(
        id, VideoCodec.APV, MediaContainer.MP4, ExportBackend.OPEN_APV, ImplementationStatus.IMPLEMENTED,
        title = title, encoderName = "liboapv", encoderProfile = profile, pixelFormat = pixelFormat,
        audio = ExportAudioCodec.AAC, defaultQuality = 20,
    )
}
