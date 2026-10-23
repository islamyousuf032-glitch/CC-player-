package com.ccplayer.core.media.export

enum class VideoCodec(val displayName: String, val mimeType: String?) {
    H264("H.264 / AVC", "video/avc"),
    HEVC("H.265 / HEVC", "video/hevc"),
    APV("APV", "video/apv"),
    PRORES_422_HQ("ProRes 422 HQ", null),
    PRORES_4444("ProRes 4444", null),
    DNXHR("DNxHR", null),
    FFV1("FFV1", null),
}

enum class MediaContainer(val extension: String) {
    MP4("mp4"),
    MOV("mov"),
    MATROSKA("mkv"),
}

enum class ExportBackend {
    MEDIA3,
    FFMPEG,
    OPEN_APV,
}

enum class ImplementationStatus {
    IMPLEMENTED,
    PLANNED,
}

data class ExportProfile(
    val id: String,
    val codec: VideoCodec,
    val container: MediaContainer,
    val backend: ExportBackend,
    val status: ImplementationStatus,
) {
    init {
        require(id.isNotBlank()) { "Profile id cannot be blank" }
    }
}

object ExportProfiles {
    val H264 = ExportProfile("h264-mp4", VideoCodec.H264, MediaContainer.MP4, ExportBackend.MEDIA3, ImplementationStatus.IMPLEMENTED)
    val HEVC = ExportProfile("hevc-mp4", VideoCodec.HEVC, MediaContainer.MP4, ExportBackend.MEDIA3, ImplementationStatus.IMPLEMENTED)
    val APV = ExportProfile("apv-mp4", VideoCodec.APV, MediaContainer.MP4, ExportBackend.MEDIA3, ImplementationStatus.IMPLEMENTED)
    val PRORES = ExportProfile("prores-422-hq-mov", VideoCodec.PRORES_422_HQ, MediaContainer.MOV, ExportBackend.FFMPEG, ImplementationStatus.PLANNED)
    val PRORES_4444 = ExportProfile("prores-4444-mov", VideoCodec.PRORES_4444, MediaContainer.MOV, ExportBackend.FFMPEG, ImplementationStatus.PLANNED)
    val APV_SOFTWARE = ExportProfile("apv-software-mp4", VideoCodec.APV, MediaContainer.MP4, ExportBackend.OPEN_APV, ImplementationStatus.PLANNED)

    val current = listOf(H264, HEVC, APV)

    fun validate(profile: ExportProfile): List<String> = buildList {
        when (profile.codec) {
            VideoCodec.H264, VideoCodec.HEVC, VideoCodec.APV -> {
                if (profile.container != MediaContainer.MP4) add("${profile.codec.displayName} currently targets MP4")
            }
            VideoCodec.PRORES_422_HQ, VideoCodec.PRORES_4444, VideoCodec.DNXHR -> {
                if (profile.container != MediaContainer.MOV) add("${profile.codec.displayName} requires MOV in this pipeline")
            }
            VideoCodec.FFV1 -> {
                if (profile.container != MediaContainer.MATROSKA) add("FFV1 requires Matroska in this pipeline")
            }
        }
        if (profile.backend == ExportBackend.MEDIA3 && profile.codec.mimeType == null) {
            add("Media3 cannot encode ${profile.codec.displayName}; use the native backend")
        }
    }
}
