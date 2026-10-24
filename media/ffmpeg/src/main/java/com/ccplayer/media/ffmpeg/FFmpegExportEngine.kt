package com.ccplayer.media.ffmpeg

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.ccplayer.core.media.color.ColorGradeMatrix
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class FFmpegExportEngine(context: Context) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun runtimeInfo(): FFmpegRuntimeInfo = parseRuntimeInfo(FFmpegNative.nativeRuntimeInfo())

    fun probe(uri: Uri): NativeMediaInfo = withInputPath(uri) { path ->
        parseMediaInfo(FFmpegNative.nativeProbe(path))
    }

    /** Blocking call: invoke on Dispatchers.IO. Cancellation is checked inside native decode/encode. */
    fun export(
        request: NativeExportRequest,
        onProgress: (percent: Int, positionUs: Long) -> Unit = { _, _ -> },
    ): NativeExportResult {
        check(running.compareAndSet(false, true)) { "A native export is already running" }
        cancelled.set(false)
        try {
            request.destination.parentFile?.mkdirs()
            if (request.destination.exists() && !request.destination.delete()) {
                throw IOException("Could not replace temporary native export")
            }
            return withInputPath(request.input) { path ->
                val useHdr = request.useHdr ?: if (request.grade.isIdentity) {
                    false
                } else {
                    parseMediaInfo(FFmpegNative.nativeProbe(path)).primaryVideo?.isHdr == true
                }
                val matrix = ColorGradeMatrix.create(request.grade, useHdr)
                val listener = object {
                    @Suppress("UNUSED")
                    fun onProgress(percent: Int, positionUs: Long) = onProgress(percent, positionUs)

                    @Suppress("UNUSED")
                    fun isCancelled(): Boolean = cancelled.get()
                }
                val result = FFmpegNative.nativeExport(
                    inputPath = path,
                    outputPath = request.destination.absolutePath,
                    codec = request.codec,
                    profile = request.profile,
                    pixelFormat = request.pixelFormat,
                    audioCodec = request.audioCodec.nativeName,
                    width = request.width,
                    height = request.height,
                    frameRateNum = request.frameRateNum,
                    frameRateDen = request.frameRateDen,
                    audioBitRate = request.audioBitRate,
                    audioSampleRate = request.audioSampleRate,
                    videoQuality = request.videoQuality,
                    threadCount = request.threadCount,
                    trimStartUs = request.trimStartUs,
                    trimEndUs = request.trimEndUs,
                    useHdr = useHdr,
                    grade = matrix,
                    listener = listener,
                )
                parseExportResult(result)
            }
        } finally {
            running.set(false)
        }
    }

    fun cancel() {
        cancelled.set(true)
    }

    private fun <T> withInputPath(uri: Uri, block: (String) -> T): T {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("File URI has no path")
            return block(path)
        }
        val descriptor = appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Could not open input document")
        return descriptor.use { pfd ->
            if (pfd.isSeekable()) {
                // Keep the ParcelFileDescriptor alive for the entire native demux/decode operation.
                block("/proc/self/fd/${pfd.fd}")
            } else {
                withCopiedInput(uri, block)
            }
        }
    }

    private fun ParcelFileDescriptor.isSeekable(): Boolean = try {
        Os.lseek(fileDescriptor, 0L, OsConstants.SEEK_CUR)
        true
    } catch (_: Exception) {
        false
    }

    private fun <T> withCopiedInput(uri: Uri, block: (String) -> T): T {
        val directory = File(appContext.cacheDir, "native-inputs").apply { mkdirs() }
        val suffix = queryDisplayName(uri).substringAfterLast('.', "")
            .filter { it.isLetterOrDigit() }.take(10).let { if (it.isBlank()) ".media" else ".$it" }
        val temporary = File(directory, "${UUID.randomUUID()}$suffix")
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not read non-seekable input")
            input.buffered().use { source ->
                FileOutputStream(temporary).buffered().use { target ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                }
            }
            return block(temporary.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "input.media"
    }
}

private fun parseMediaInfo(json: String): NativeMediaInfo {
    val root = JSONObject(json)
    val streams = buildList {
        val array = root.getJSONArray("streams")
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(NativeStreamInfo(
                index = item.getInt("index"),
                type = item.getString("type"),
                codec = item.getString("codec"),
                codecLongName = item.optString("codecLongName"),
                profile = item.optString("profile"),
                profileId = item.optInt("profileId", -99),
                pixelFormat = item.optString("pixelFormat"),
                sampleFormat = item.optString("sampleFormat"),
                language = item.optString("language"),
                width = item.optInt("width"),
                height = item.optInt("height"),
                bitDepth = item.optInt("bitDepth"),
                sampleRate = item.optInt("sampleRate"),
                channels = item.optInt("channels"),
                bitRate = item.optLong("bitRate"),
                durationUs = item.optLong("durationUs", -1),
                startTimeUs = item.optLong("startTimeUs"),
                frameRateNum = item.optInt("frameRateNum"),
                frameRateDen = item.optInt("frameRateDen"),
                timeBaseNum = item.optInt("timeBaseNum"),
                timeBaseDen = item.optInt("timeBaseDen", 1),
                sampleAspectRatioNum = item.optInt("sampleAspectRatioNum"),
                sampleAspectRatioDen = item.optInt("sampleAspectRatioDen", 1),
                colorPrimaries = item.optInt("colorPrimaries", 2),
                colorTransfer = item.optInt("colorTransfer", 2),
                colorSpace = item.optInt("colorSpace", 2),
                colorRange = item.optInt("colorRange"),
                codecTag = item.optLong("codecTag"),
                frameCount = item.optLong("frameCount"),
                rotationDegrees = item.optDouble("rotationDegrees"),
                attachedPicture = item.optBoolean("attachedPicture"),
                defaultDisposition = item.optBoolean("defaultDisposition"),
            ))
        }
    }
    return NativeMediaInfo(
        format = root.getString("format"),
        formatLongName = root.optString("formatLongName"),
        durationUs = root.optLong("durationUs"),
        bitRate = root.optLong("bitRate"),
        startTimeUs = root.optLong("startTimeUs"),
        streams = streams,
    )
}

private fun parseExportResult(json: String): NativeExportResult {
    val item = JSONObject(json)
    return NativeExportResult(
        cancelled = item.getBoolean("cancelled"),
        verified = item.optBoolean("verified"),
        codec = item.optString("codec"),
        profile = item.optString("profile"),
        pixelFormat = item.optString("pixelFormat"),
        audioCodec = item.optString("audioCodec"),
        width = item.optInt("width"),
        height = item.optInt("height"),
        bitDepth = item.optInt("bitDepth"),
        frameRateNum = item.optInt("frameRateNum"),
        frameRateDen = item.optInt("frameRateDen"),
        durationUs = item.optLong("durationUs"),
        fileSizeBytes = item.optLong("fileSizeBytes"),
        encodedFrames = item.optLong("encodedFrames"),
    )
}

private fun parseRuntimeInfo(json: String): FFmpegRuntimeInfo {
    val root = JSONObject(json)
    fun map(name: String): Map<String, Boolean> = buildMap {
        val item = root.getJSONObject(name)
        item.keys().forEach { key -> put(key, item.getBoolean(key)) }
    }
    return FFmpegRuntimeInfo(
        version = root.getString("version"),
        configuration = root.getString("configuration"),
        license = root.getString("license"),
        encoders = map("encoders"),
        decoders = map("decoders"),
    )
}
