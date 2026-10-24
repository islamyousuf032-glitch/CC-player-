package com.ccplayer.app.player

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.ccplayer.core.media.color.ColorGrade
import com.ccplayer.core.media.export.ExportAudioCodec
import com.ccplayer.core.media.export.ExportProfile
import com.ccplayer.core.media.export.ExportProfiles
import com.ccplayer.core.media.export.ImplementationStatus
import com.ccplayer.core.media.format.InputBackend
import com.ccplayer.core.media.format.InputFormatRouter
import com.ccplayer.media.android.codec.DeviceCodecCapabilities
import com.ccplayer.media.android.codec.DeviceCodecReport
import com.ccplayer.media.android.export.Media3ExportEngine
import com.ccplayer.media.android.playback.Media3PlaybackEngine
import com.ccplayer.media.android.playback.PlaybackEngine
import com.ccplayer.media.android.playback.PlaybackSnapshot
import com.ccplayer.media.mcraw.McrawDecoder
import com.ccplayer.media.mcraw.McrawInfo
import com.ccplayer.media.ffmpeg.FFmpegExportEngine
import com.ccplayer.media.ffmpeg.FFmpegRuntimeInfo
import com.ccplayer.media.ffmpeg.NativeAudioCodec
import com.ccplayer.media.ffmpeg.NativeExportRequest
import com.ccplayer.media.ffmpeg.NativeMediaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class MediaKind { NONE, VIDEO, MCRAW, UNSUPPORTED }
enum class VideoResizeMode { FIT, FILL, CROP }
enum class ExportStatus { IDLE, RUNNING, COPYING, SUCCESS, ERROR }

data class ExportUiState(
    val status: ExportStatus = ExportStatus.IDLE,
    val progress: Int? = null,
    val message: String? = null,
    val selectedProfileId: String = ExportProfiles.H264.id,
)

data class PlayerUiState(
    val mediaName: String? = null,
    val mediaKind: MediaKind = MediaKind.NONE,
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val grade: ColorGrade = ColorGrade.IDENTITY,
    val resizeMode: VideoResizeMode = VideoResizeMode.FIT,
    val isBusy: Boolean = false,
    val notice: String? = null,
    val unsupportedDetail: String? = null,
    val mcrawInfo: McrawInfo? = null,
    val mcrawPreview: Bitmap? = null,
    val mcrawFrameIndex: Int = 0,
    val codecReport: DeviceCodecReport? = null,
    val nativeInfo: NativeMediaInfo? = null,
    val ffmpegRuntime: FFmpegRuntimeInfo? = null,
    val nativeError: String? = null,
    val showCodecReport: Boolean = false,
    val showGradeSheet: Boolean = false,
    val showExportSheet: Boolean = false,
    val export: ExportUiState = ExportUiState(),
)

@UnstableApi
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val playbackEngine = Media3PlaybackEngine(application)
    private val exportEngine = Media3ExportEngine(application)
    private val nativeExportEngine = FFmpegExportEngine(application)
    private val mcrawDecoder = McrawDecoder(application)
    private val contentResolver = application.contentResolver
    private val mutableUiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = mutableUiState.asStateFlow()
    val engine: PlaybackEngine get() = playbackEngine

    private var currentUri: Uri? = null
    private var mcrawJob: Job? = null
    private var probeJob: Job? = null
    private var exportJob: Job? = null
    private var exportDestinationUri: Uri? = null
    private var exportTemporaryFile: File? = null

    init {
        viewModelScope.launch {
            playbackEngine.state.collect { playback ->
                mutableUiState.update { it.copy(playback = playback) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val report = DeviceCodecCapabilities.inspect()
            mutableUiState.update { it.copy(codecReport = report) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { nativeExportEngine.runtimeInfo() }
                .onSuccess { info -> mutableUiState.update { it.copy(ffmpegRuntime = info) } }
                .onFailure { error -> mutableUiState.update { it.copy(nativeError = error.message ?: "Native backend could not load") } }
        }
    }

    fun openMedia(uri: Uri) {
        if (exportEngine.isRunning || nativeExportEngine.isRunning || mutableUiState.value.export.status == ExportStatus.COPYING) {
            setNotice("Finish or cancel the current export before opening another file")
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        mcrawJob?.cancel()
        probeJob?.cancel()
        playbackEngine.clear()
        currentUri = uri
        val name = queryDisplayName(uri)
        val route = InputFormatRouter.route(name)
        mutableUiState.value = PlayerUiState(
            mediaName = name,
            mediaKind = MediaKind.NONE,
            codecReport = mutableUiState.value.codecReport,
            ffmpegRuntime = mutableUiState.value.ffmpegRuntime,
            nativeError = mutableUiState.value.nativeError,
            isBusy = true,
        )

        if (route.backend != InputBackend.MCRAW_NATIVE && route.backend != InputBackend.VENDOR_SDK) {
            probeNative(uri)
        }
        when (route.backend) {
            InputBackend.MCRAW_NATIVE -> openMcraw(uri)
            InputBackend.PLATFORM_FIRST -> {
                playbackEngine.setColorGrade(ColorGrade.IDENTITY)
                playbackEngine.open(uri)
                mutableUiState.update { it.copy(mediaKind = MediaKind.VIDEO, isBusy = false) }
            }
            else -> mutableUiState.update {
                it.copy(
                    mediaKind = MediaKind.UNSUPPORTED,
                    isBusy = false,
                    unsupportedDetail = route.detail,
                )
            }
        }
    }

    private fun probeNative(uri: Uri) {
        probeJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { nativeExportEngine.probe(uri) }
                .onSuccess { info ->
                    if (currentUri == uri) mutableUiState.update { it.copy(nativeInfo = info) }
                }
        }
    }

    private fun openMcraw(uri: Uri) {
        mutableUiState.update { it.copy(mediaKind = MediaKind.MCRAW, isBusy = true) }
        mcrawJob = viewModelScope.launch {
            runCatching {
                val info = mcrawDecoder.probe(uri)
                val preview = mcrawDecoder.renderDraftPreview(uri, frameIndex = 0)
                info to preview
            }.onSuccess { (info, preview) ->
                if (currentUri == uri) {
                    mutableUiState.update {
                        it.copy(
                            isBusy = false,
                            mcrawInfo = info,
                            mcrawPreview = preview,
                            mcrawFrameIndex = 0,
                        )
                    }
                }
            }.onFailure { error ->
                if (currentUri == uri) {
                    mutableUiState.update {
                        it.copy(
                            isBusy = false,
                            mediaKind = MediaKind.UNSUPPORTED,
                            unsupportedDetail = "MCRAW decode failed: ${error.message ?: "unknown native error"}",
                        )
                    }
                }
            }
        }
    }

    fun renderMcrawFrame(frameIndex: Int) {
        val uri = currentUri ?: return
        val info = mutableUiState.value.mcrawInfo ?: return
        if (info.frameCount <= 0) return
        val target = frameIndex.coerceIn(0, info.frameCount - 1)
        mcrawJob?.cancel()
        mutableUiState.update { it.copy(mcrawFrameIndex = target, isBusy = true) }
        mcrawJob = viewModelScope.launch {
            runCatching { mcrawDecoder.renderDraftPreview(uri, target) }
                .onSuccess { preview ->
                    if (currentUri == uri) {
                        mutableUiState.update {
                            it.copy(mcrawPreview = preview, isBusy = false, mcrawFrameIndex = target)
                        }
                    }
                }
                .onFailure { error ->
                    if (currentUri == uri) {
                        mutableUiState.update {
                            it.copy(isBusy = false, notice = "Frame decode failed: ${error.message}")
                        }
                    }
                }
        }
    }

    fun playPause() = playbackEngine.playPause()
    fun pause() = playbackEngine.pause()
    fun seekBy(deltaMs: Long) = playbackEngine.seekBy(deltaMs)
    fun beginScrubbing() = playbackEngine.beginScrubbing()
    fun scrubTo(positionMs: Long) = playbackEngine.scrubTo(positionMs)
    fun endScrubbing(positionMs: Long) = playbackEngine.endScrubbing(positionMs)

    fun cycleSpeed() {
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 4f)
        val current = mutableUiState.value.playback.speed
        val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
        playbackEngine.setSpeed(next)
    }

    fun toggleRepeat() = playbackEngine.setRepeat(!mutableUiState.value.playback.repeat)

    fun cycleResizeMode() {
        val next = when (mutableUiState.value.resizeMode) {
            VideoResizeMode.FIT -> VideoResizeMode.CROP
            VideoResizeMode.CROP -> VideoResizeMode.FILL
            VideoResizeMode.FILL -> VideoResizeMode.FIT
        }
        mutableUiState.update { it.copy(resizeMode = next) }
    }

    fun setGrade(grade: ColorGrade) {
        playbackEngine.setColorGrade(grade)
        mutableUiState.update { it.copy(grade = grade) }
    }

    fun showGradeSheet(show: Boolean) = mutableUiState.update { it.copy(showGradeSheet = show) }
    fun showExportSheet(show: Boolean) = mutableUiState.update { it.copy(showExportSheet = show) }
    fun showCodecReport(show: Boolean) = mutableUiState.update { it.copy(showCodecReport = show) }

    fun selectExportProfile(profileId: String) {
        mutableUiState.update { it.copy(export = it.export.copy(selectedProfileId = profileId)) }
    }

    fun isExportProfileEnabled(profile: ExportProfile): Boolean {
        if (profile.status != ImplementationStatus.IMPLEMENTED) return false
        if (profile.usesNativeBackend) {
            return mutableUiState.value.ffmpegRuntime?.encoders?.get(profile.encoderName) == true
        }
        val report = mutableUiState.value.codecReport ?: return profile == ExportProfiles.H264
        val mime = profile.codec.mimeType ?: return false
        return report.support(mime).canEncode
    }

    fun suggestedExportFileName(): String {
        val base = mutableUiState.value.mediaName
            ?.substringBeforeLast('.')
            ?.sanitizeForFileName()
            ?.take(48)
            ?: "CC-Player"
        val profile = ExportProfiles.byId(mutableUiState.value.export.selectedProfileId)
        return "$base-${profile.id}.${profile.container.extension}"
    }

    fun startExport(destinationUri: Uri) {
        val input = currentUri ?: return
        val state = mutableUiState.value
        if (state.mediaKind != MediaKind.VIDEO || exportEngine.isRunning || nativeExportEngine.isRunning ||
            state.export.status == ExportStatus.RUNNING || state.export.status == ExportStatus.COPYING) return
        val profile = ExportProfiles.byId(state.export.selectedProfileId)
        if (!isExportProfileEnabled(profile)) {
            mutableUiState.update {
                it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = "${profile.title} encoder is unavailable in this runtime"))
            }
            return
        }

        playbackEngine.pause()
        exportDestinationUri = destinationUri
        val temporary = File(
            getApplication<Application>().cacheDir,
            "exports/${UUID.randomUUID()}.${profile.container.extension}",
        )
        exportTemporaryFile = temporary
        mutableUiState.update {
            it.copy(
                showExportSheet = false,
                export = it.export.copy(status = ExportStatus.RUNNING, progress = null, message = null),
            )
        }

        if (profile.usesNativeBackend) {
            startNativeExport(input, temporary, profile, state.grade)
            return
        }
        exportEngine.start(
            input = input,
            destination = temporary,
            codec = profile.codec,
            grade = state.grade,
            listener = object : Media3ExportEngine.Listener {
                override fun onCompleted(outputFile: File) {
                    mutableUiState.update { it.copy(export = it.export.copy(status = ExportStatus.COPYING)) }
                    copyExportToDestination(outputFile)
                }

                override fun onError(message: String) {
                    exportTemporaryFile?.delete()
                    mutableUiState.update {
                        it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = message))
                    }
                }

                override fun onFallback(message: String) = setNotice(message)
            },
        )
        startExportProgressUpdates()
    }

    private fun startNativeExport(input: Uri, temporary: File, profile: ExportProfile, grade: ColorGrade) {
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            try {
                val request = NativeExportRequest(
                    input = input,
                    destination = temporary,
                    codec = requireNotNull(profile.encoderName),
                    profile = profile.encoderProfile.orEmpty(),
                    pixelFormat = requireNotNull(profile.pixelFormat),
                    audioCodec = profile.audio.toNativeAudioCodec(),
                    videoQuality = profile.defaultQuality,
                    grade = grade,
                )
                val result = withContext(Dispatchers.IO) {
                    nativeExportEngine.export(request) { percent, _ ->
                        mutableUiState.update { it.copy(export = it.export.copy(progress = percent)) }
                    }
                }
                if (result.cancelled) {
                    mutableUiState.update { it.copy(export = ExportUiState(selectedProfileId = it.export.selectedProfileId)) }
                } else {
                    mutableUiState.update { it.copy(export = it.export.copy(status = ExportStatus.COPYING)) }
                    val summary = "Verified ${result.encodedFrames} decoded frames • ${result.codec} ${result.profile} • " +
                        "${result.pixelFormat} (${result.bitDepth}-bit) • ${result.width}×${result.height}"
                    copyExportToDestination(temporary, summary)
                }
            } catch (cancelled: CancellationException) {
                temporary.delete()
                throw cancelled
            } catch (error: Throwable) {
                temporary.delete()
                mutableUiState.update {
                    it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = error.message ?: "Native export failed"))
                }
            }
        }
    }

    private fun startExportProgressUpdates() {
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            while (exportEngine.isRunning) {
                val progress = exportEngine.progress()
                mutableUiState.update { it.copy(export = it.export.copy(progress = progress)) }
                delay(250)
            }
        }
    }

    private fun copyExportToDestination(
        outputFile: File,
        successMessage: String = "Export saved with the same color effect used in preview",
    ) {
        val destination = exportDestinationUri
        if (destination == null) {
            outputFile.delete()
            exportTemporaryFile = null
            mutableUiState.update {
                it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = "Missing export destination"))
            }
            return
        }
        exportJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(destination, "wt")
                        ?: throw IOException("Could not open export destination")
                    output.buffered().use { target ->
                        outputFile.inputStream().buffered().use { source ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                target.write(buffer, 0, count)
                            }
                        }
                    }
                }
                mutableUiState.update {
                    it.copy(
                        export = it.export.copy(
                            status = ExportStatus.SUCCESS,
                            progress = 100,
                            message = successMessage,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableUiState.update {
                    it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = error.message))
                }
            } finally {
                outputFile.delete()
                if (exportTemporaryFile == outputFile) {
                    exportTemporaryFile = null
                    exportDestinationUri = null
                }
            }
        }
    }

    fun cancelExport() {
        exportEngine.cancel()
        nativeExportEngine.cancel()
        exportJob?.cancel()
        exportTemporaryFile?.delete()
        exportTemporaryFile = null
        exportDestinationUri?.let { uri ->
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
        }
        exportDestinationUri = null
        mutableUiState.update { it.copy(export = ExportUiState(selectedProfileId = it.export.selectedProfileId)) }
    }

    fun dismissExportResult() {
        mutableUiState.update {
            it.copy(export = ExportUiState(selectedProfileId = it.export.selectedProfileId))
        }
    }

    fun consumeNotice() = mutableUiState.update { it.copy(notice = null) }

    private fun setNotice(message: String) = mutableUiState.update { it.copy(notice = message) }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported media"
    }

    override fun onCleared() {
        mcrawJob?.cancel()
        probeJob?.cancel()
        exportJob?.cancel()
        exportEngine.cancel()
        nativeExportEngine.cancel()
        exportTemporaryFile?.delete()
        playbackEngine.close()
        super.onCleared()
    }
}

private fun ExportAudioCodec.toNativeAudioCodec(): NativeAudioCodec = when (this) {
    ExportAudioCodec.AAC -> NativeAudioCodec.AAC
    ExportAudioCodec.PCM16 -> NativeAudioCodec.PCM_S16LE
    ExportAudioCodec.PCM24 -> NativeAudioCodec.PCM_S24LE
    ExportAudioCodec.FLAC -> NativeAudioCodec.FLAC
    ExportAudioCodec.COPY -> NativeAudioCodec.COPY
    ExportAudioCodec.NONE -> NativeAudioCodec.NONE
}

private fun String.sanitizeForFileName(): String =
    replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifBlank { "CC-Player" }
