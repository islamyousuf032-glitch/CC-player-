package com.ccplayer.media.android.codec

import android.media.MediaCodecList
import android.os.Build
import java.util.Locale

data class CodecComponent(
    val name: String,
    val isEncoder: Boolean,
    val isHardwareAccelerated: Boolean,
)

data class CodecSupport(
    val mimeType: String,
    val components: List<CodecComponent>,
) {
    val canDecode: Boolean get() = components.any { !it.isEncoder }
    val canEncode: Boolean get() = components.any { it.isEncoder }
    val hasHardwareDecoder: Boolean get() = components.any { !it.isEncoder && it.isHardwareAccelerated }
    val hasHardwareEncoder: Boolean get() = components.any { it.isEncoder && it.isHardwareAccelerated }
}

data class DeviceCodecReport(val supported: List<CodecSupport>) {
    fun support(mimeType: String): CodecSupport = supported.firstOrNull {
        it.mimeType.equals(mimeType, ignoreCase = true)
    } ?: CodecSupport(mimeType, emptyList())
}

/** Queries the actual device instead of treating an Android version table as device support. */
object DeviceCodecCapabilities {
    val commonVideoMimeTypes = listOf(
        "video/avc",
        "video/hevc",
        "video/x-vnd.on2.vp9",
        "video/av01",
        "video/apv",
    )

    fun inspect(): DeviceCodecReport {
        val byMime = linkedMapOf<String, MutableList<CodecComponent>>()
        val infos = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }
            .getOrElse { emptyList() }

        infos.filterNot { info ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching { info.isAlias }.getOrDefault(false)
        }.forEach { info ->
            val isEncoder = info.isEncoder
            // Hardware/alias classification was added in API 29; remain conservative on 26–28.
            val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                runCatching { info.isHardwareAccelerated }.getOrDefault(false)
            val types = runCatching { info.supportedTypes.toList() }.getOrElse { emptyList() }
            types.forEach { type ->
                val mime = type.lowercase(Locale.ROOT)
                byMime.getOrPut(mime) { mutableListOf() }.add(
                    CodecComponent(info.name, isEncoder, isHardware),
                )
            }
        }
        return DeviceCodecReport(
            byMime.map { (mime, components) -> CodecSupport(mime, components.sortedBy { it.name }) },
        )
    }
}
