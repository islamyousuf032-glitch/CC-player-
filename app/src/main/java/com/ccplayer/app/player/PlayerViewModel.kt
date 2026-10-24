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
import com.ccplayer.media.android.playback.PlaybackEngine
import com.ccplayer.media.android.playback.PlaybackSnapshot
import com.ccplayer.media.mcraw.McrawDecoder
import com.ccplayer.media.mcraw.McrawInfo
import com.ccplayer.media.mcraw.McrawPlaybackController
import com.ccplayer.media.ffmpeg.FFmpegExportEngine
import com.ccplayer.media.ffmpeg.FFmpegRuntimeInfo
import com.ccplayer.media.ffmpeg.FFmpegImageDecoder
import com.ccplayer.media.ffmpeg.ImageSequenceInfo
import com.ccplayer.media.ffmpeg.ImageSequenceReader
import com.ccplayer.media.ffmpeg.SequencePlaybackController
import com.ccplayer.media.ffmpeg.SequencePlaybackState
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

enum class MediaKind { NONE, VIDEO, MCRAW, SEQUENCE, UNSUPPORTED }
enum class VideoResizeMode { FIT, FILL, CROP }
enum class ExportStatus { IDLE, RUNNING, COPYING, SUCCESS, ERROR }

data class ExportUiState(
    val status: ExportStatus = ExportStatus.IDLE,
    val progress: Int? = null,
    val etaSeconds: Long? = null,
    val message: String? = null,
    val selectedProfileId: String = ExportProfiles.H264.id,
)

data class PlayerUiState(
    val mediaName: String? = null,
    val mediaKind: MediaKind = MediaKind.NONE,
    val recentMedia: List<RecentMedia> = emptyList(),
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val playbackBackend: PlaybackBackend = PlaybackBackend.NONE,
    val grade: ColorGrade = ColorGrade.IDENTITY,
    val resizeMode: VideoResizeMode = VideoResizeMode.FIT,
    val isBusy: Boolean = false,
    val notice: String? = null,
    val unsupportedDetail: String? = null,
    val mcrawInfo: McrawInfo? = null,
    val mcrawPreview: Bitmap? = null,
    val mcrawFrameIndex: Int = 0,
    val mcrawPlaying: Boolean = false,
    val mcrawPositionUs: Long = 0,
    val mcrawDurationUs: Long = 0,
    val mcrawDroppedFrames: Int = 0,
    val mcrawPlaybackError: String? = null,
    val sequenceInfo: ImageSequenceInfo? = null,
    val sequencePlayback: SequencePlaybackState = SequencePlaybackState(),
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
    private val playbackEngine = RoutingPlaybackEngine(application)
    private val exportEngine = Media3ExportEngine(application)
    private val nativeExportEngine = FFmpegExportEngine(application)
    private val mcrawDecoder = McrawDecoder(application)
    private val mcrawPlayback = McrawPlaybackController(application)
    private val sequenceReader = ImageSequenceReader(application)
    private val sequencePlayback = SequencePlaybackController(FFmpegImageDecoder(application))
    private val contentResolver = application.contentResolver
    private val preferences = PlayerPreferences(application)
    private val mutableUiState = MutableStateFlow(
        PlayerUiState(
            recentMedia = preferences.recentMedia(),
            grade = preferences.savedGrade(),
            resizeMode = preferences.savedResizeMode(),
        ),
    )
    val uiState: StateFlow<PlayerUiState> = mutableUiState.asStateFlow()
    val engine: PlaybackEngine get() = playbackEngine

    private var currentUri: Uri? = null
    private var mcrawJob: Job? = null
    private var sequenceJob: Job? = null
    private var probeJob: Job? = null
    private var exportJob: Job? = null
    private var exportDestinationUri: Uri? = null
    private var exportTemporaryFile: File? = null
    private var exportStartedAtNanos = 0L

    init {
        playbackEngine.setColorGrade(mutableUiState.value.grade)
        playbackEngine.setSpeed(preferences.savedSpeed())
        playbackEngine.setRepeat(preferences.savedRepeat())
        viewModelScope.launch {
            playbackEngine.state.collect { playback ->
                mutableUiState.update { it.copy(playback = playback) }
            }
        }
        viewModelScope.launch {
            mcrawPlayback.state.collect { raw ->
                mutableUiState.update {
                    it.copy(
                        mcrawPreview = raw.preview,
                        mcrawFrameIndex = raw.frameIndex,
                        mcrawPlaying = raw.isPlaying,
                        mcrawPositionUs = raw.positionUs,
                        mcrawDurationUs = raw.durationUs,
                        mcrawDroppedFrames = raw.droppedFrames,
                        mcrawPlaybackError = raw.error,
                    )
                }
            }
        }
        viewModelScope.launch {
            sequencePlayback.state.collect { sequence ->
                mutableUiState.update { it.copy(sequencePlayback = sequence) }
            }
        }
        viewModelScope.launch {
            playbackEngine.backend.collect { backend ->
                mutableUiState.update { it.copy(playbackBackend = backend) }
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

        saveCurrentPosition()
        mcrawJob?.cancel()
        mcrawPlayback.pause()
        sequenceJob?.cancel()
        sequencePlayback.pause()
        probeJob?.cancel()
        playbackEngine.clear()
        currentUri = uri
        val name = queryDisplayName(uri)
        val route = InputFormatRouter.route(name)
        val previous = mutableUiState.value
        val recents = preferences.recordOpen(uri, name)
        mutableUiState.value = PlayerUiState(
            mediaName = name,
            mediaKind = MediaKind.NONE,
            recentMedia = recents,
            grade = previous.grade,
            resizeMode = previous.resizeMode,
            codecReport = previous.codecReport,
            ffmpegRuntime = previous.ffmpegRuntime,
            nativeError = previous.nativeError,
            isBusy = true,
        )

        if (route.backend != InputBackend.MCRAW_NATIVE && route.backend != InputBackend.VENDOR_SDK) {
            probeNative(uri)
        }
        when (route.backend) {
            InputBackend.MCRAW_NATIVE -> openMcraw(uri)
            InputBackend.PLATFORM_FIRST -> {
                playbackEngine.setColorGrade(mutableUiState.value.grade)
                playbackEngine.open(uri)
                resumeVideoPosition(uri)
                mutableUiState.update { it.copy(mediaKind = MediaKind.VIDEO, isBusy = false) }
            }
            InputBackend.FFMPEG_NATIVE -> {
                playbackEngine.setColorGrade(mutableUiState.value.grade)
                playbackEngine.openNative(uri)
                resumeVideoPosition(uri)
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

    fun openRecent(uri: Uri) {
        if (DocumentsContract.isTreeUri(uri)) openSequence(uri) else openMedia(uri)
    }

    fun openSequence(treeUri: Uri) {
        if (exportEngine.isRunning || nativeExportEngine.isRunning || mutableUiState.value.export.status == ExportStatus.COPYING) {
            setNotice("Finish or cancel the current export before opening another file")
            return
        }
        runCatching { contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        saveCurrentPosition()
        mcrawJob?.cancel()
        mcrawPlayback.pause()
        sequenceJob?.cancel()
        sequencePlayback.pause()
        probeJob?.cancel()
        playbackEngine.clear()
        currentUri = treeUri
        val previous = mutableUiState.value
        val sequenceName = "Sequence • ${DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast('/')}"
        val recents = preferences.recordOpen(treeUri, sequenceName)
        mutableUiState.value = PlayerUiState(
            mediaName = sequenceName,
            recentMedia = recents,
            grade = previous.grade,
            resizeMode = previous.resizeMode,
            codecReport = previous.codecReport,
            ffmpegRuntime = previous.ffmpegRuntime,
            nativeError = previous.nativeError,
            isBusy = true,
        )
        sequenceJob = viewModelScope.launch {
            runCatching { sequenceReader.read(treeUri) }
                .onSuccess { info ->
                    if (currentUri == treeUri) {
                        mutableUiState.update { it.copy(mediaKind = MediaKind.SEQUENCE, sequenceInfo = info, isBusy = false) }
                        sequencePlayback.open(info, startPositionUs = preferences.positionFor(treeUri) * 1_000L)
                    }
                }
                .onFailure { error ->
                    if (currentUri == treeUri) {
                        mutableUiState.update {
                            it.copy(mediaKind = MediaKind.UNSUPPORTED, isBusy = false, unsupportedDetail = error.message)
                        }
                    }
                }
        }
    }

    fun playSequencePause() = sequencePlayback.playPause()
    fun renderSequenceFrame(index: Int) = sequencePlayback.seekFrame(index)
    fun cycleSequenceFrameRate() {
        val rates = listOf(24000 to 1001, 24 to 1, 25 to 1, 30000 to 1001, 30 to 1, 60 to 1)
        val state = mutableUiState.value.sequencePlayback
        val current = state.frameRateNum.toDouble() / state.frameRateDen
        val next = rates.firstOrNull { it.first.toDouble() / it.second > current + 0.001 } ?: rates.first()
        sequencePlayback.setFrameRate(next.first, next.second)
    }

    private fun resumeVideoPosition(uri: Uri) {
        val saved = preferences.positionFor(uri)
        if (saved > 500) playbackEngine.scrubTo(saved)
    }

    private fun saveCurrentPosition() {
        val uri = currentUri ?: return
        val position = when (mutableUiState.value.mediaKind) {
            MediaKind.VIDEO -> mutableUiState.value.playback.positionMs
            MediaKind.MCRAW -> mutableUiState.value.mcrawPositionUs / 1_000L
            MediaKind.SEQUENCE -> mutableUiState.value.sequencePlayback.positionUs / 1_000L
            else -> return
        }
        preferences.savePosition(uri, position)
    }

    fun removeRecent(uri: Uri) {
        mutableUiState.update { it.copy(recentMedia = preferences.removeRecent(uri)) }
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
                mcrawPlayback.open(uri, info, preferences.positionFor(uri) * 1_000L)
                info
            }.onSuccess { info ->
                if (currentUri == uri) {
                    mutableUiState.update {
                        it.copy(isBusy = false, mcrawInfo = info, mcrawFrameIndex = 0)
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
        val info = mutableUiState.value.mcrawInfo ?: return
        if (info.frameCount <= 0) return
        mcrawPlayback.seekFrame(frameIndex.coerceIn(0, info.frameCount - 1))
    }

    fun playMcrawPause() = mcrawPlayback.playPause()
    fun playPause() = playbackEngine.playPause()
    fun pause() {
        saveCurrentPosition()
        playbackEngine.pause()
        mcrawPlayback.pause()
        sequencePlayback.pause()
    }
    fun seekBy(deltaMs: Long) = playbackEngine.seekBy(deltaMs)
    fun beginScrubbing() = playbackEngine.beginScrubbing()
    fun scrubTo(positionMs: Long) = playbackEngine.scrubTo(positionMs)
    fun endScrubbing(positionMs: Long) = playbackEngine.endScrubbing(positionMs)

    fun cycleSpeed() {
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 4f)
        val current = mutableUiState.value.playback.speed
        val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
        playbackEngine.setSpeed(next)
        preferences.savePlayback(next, mutableUiState.value.playback.repeat, mutableUiState.value.resizeMode, mutableUiState.value.grade)
    }

    fun toggleRepeat() {
        val repeat = !mutableUiState.value.playback.repeat
        playbackEngine.setRepeat(repeat)
        preferences.savePlayback(mutableUiState.value.playback.speed, repeat, mutableUiState.value.resizeMode, mutableUiState.value.grade)
    }

    fun cycleResizeMode() {
        val next = when (mutableUiState.value.resizeMode) {
            VideoResizeMode.FIT -> VideoResizeMode.CROP
            VideoResizeMode.CROP -> VideoResizeMode.FILL
            VideoResizeMode.FILL -> VideoResizeMode.FIT
        }
        mutableUiState.update { it.copy(resizeMode = next) }
        preferences.savePlayback(mutableUiState.value.playback.speed, mutableUiState.value.playback.repeat, next, mutableUiState.value.grade)
    }

    fun setGrade(grade: ColorGrade) {
        playbackEngine.setColorGrade(grade)
        mutableUiState.update { it.copy(grade = grade) }
        preferences.savePlayback(mutableUiState.value.playback.speed, mutableUiState.value.playback.repeat, mutableUiState.value.resizeMode, grade)
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

    private fun preflightExportStorage(input: Uri) {
        val sourceBytes = contentResolver.openAssetFileDescriptor(input, "r")?.use { it.length } ?: -1
        val estimated = if (sourceBytes > 0) sourceBytes.coerceAtMost(Long.MAX_VALUE / 2) * 2 else 256L * 1024 * 1024
        val required = maxOf(256L * 1024 * 1024, estimated)
        val available = getApplication<Application>().cacheDir.usableSpace
        if (available < required) {
            throw IOException("Export needs approximately ${required / 1024 / 1024} MiB free for its verified temporary output; only ${available / 1024 / 1024} MiB is available")
        }
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

        runCatching { preflightExportStorage(input) }.onFailure { error ->
            mutableUiState.update {
                it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = error.message))
            }
            return
        }
        playbackEngine.pause()
        ExportForegroundService.start(getApplication())
        exportStartedAtNanos = System.nanoTime()
        ExportForegroundService.progress(null)
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
                    ExportForegroundService.stop(getApplication())
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
                        updateExportProgress(percent)
                    }
                }
                if (result.cancelled) {
                    ExportForegroundService.stop(getApplication())
                    mutableUiState.update { it.copy(export = ExportUiState(selectedProfileId = it.export.selectedProfileId)) }
                } else {
                    mutableUiState.update { it.copy(export = it.export.copy(status = ExportStatus.COPYING)) }
                    val summary = "Verified ${result.encodedFrames} decoded frames • ${result.codec} ${result.profile} • " +
                        "${result.pixelFormat} (${result.bitDepth}-bit) • ${result.width}×${result.height}"
                    copyExportToDestination(temporary, summary)
                }
            } catch (cancelled: CancellationException) {
                ExportForegroundService.stop(getApplication())
                temporary.delete()
                throw cancelled
            } catch (error: Throwable) {
                ExportForegroundService.stop(getApplication())
                temporary.delete()
                mutableUiState.update {
                    it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = error.message ?: "Native export failed"))
                }
            }
        }
    }

    private fun updateExportProgress(percent: Int) {
        val bounded = percent.coerceIn(0, 100)
        val elapsedSeconds = (System.nanoTime() - exportStartedAtNanos) / 1_000_000_000L
        val eta = if (bounded in 1..99) (elapsedSeconds * (100 - bounded) / bounded).coerceAtLeast(0) else null
        ExportForegroundService.progress(bounded)
        mutableUiState.update { it.copy(export = it.export.copy(progress = bounded, etaSeconds = eta)) }
    }

    private fun startExportProgressUpdates() {
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            while (exportEngine.isRunning) {
                val progress = exportEngine.progress()
                updateExportProgress(progress)
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
            ExportForegroundService.stop(getApplication())
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
                ExportForegroundService.stop(getApplication())
                outputFile.delete()
                if (exportTemporaryFile == outputFile) {
                    exportTemporaryFile = null
                    exportDestinationUri = null
                }
            }
        }
    }

    fun cancelExport() {
        ExportForegroundService.stop(getApplication())
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
        sequenceJob?.cancel()
        probeJob?.cancel()
        exportJob?.cancel()
        exportEngine.cancel()
        nativeExportEngine.cancel()
        ExportForegroundService.stop(getApplication())
        exportTemporaryFile?.delete()
        saveCurrentPosition()
        playbackEngine.close()
        mcrawPlayback.close()
        sequencePlayback.close()
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
