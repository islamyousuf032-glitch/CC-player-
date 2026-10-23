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
import com.ccplayer.core.media.export.ExportProfiles
import com.ccplayer.core.media.export.VideoCodec
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val selectedCodec: VideoCodec = VideoCodec.H264,
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
    val showCodecReport: Boolean = false,
    val showGradeSheet: Boolean = false,
    val showExportSheet: Boolean = false,
    val export: ExportUiState = ExportUiState(),
)

@UnstableApi
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val playbackEngine = Media3PlaybackEngine(application)
    private val exportEngine = Media3ExportEngine(application)
    private val mcrawDecoder = McrawDecoder(application)
    private val contentResolver = application.contentResolver
    private val mutableUiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = mutableUiState.asStateFlow()
    val engine: PlaybackEngine get() = playbackEngine

    private var currentUri: Uri? = null
    private var mcrawJob: Job? = null
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
    }

    fun openMedia(uri: Uri) {
        if (exportEngine.isRunning || mutableUiState.value.export.status == ExportStatus.COPYING) {
            setNotice("Finish or cancel the current export before opening another file")
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        mcrawJob?.cancel()
        playbackEngine.clear()
        currentUri = uri
        val name = queryDisplayName(uri)
        val route = InputFormatRouter.route(name)
        mutableUiState.value = PlayerUiState(
            mediaName = name,
            mediaKind = MediaKind.NONE,
            codecReport = mutableUiState.value.codecReport,
            isBusy = true,
        )

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

    fun selectExportCodec(codec: VideoCodec) {
        mutableUiState.update { it.copy(export = it.export.copy(selectedCodec = codec)) }
    }

    fun isExportCodecEnabled(codec: VideoCodec): Boolean {
        val report = mutableUiState.value.codecReport ?: return codec == VideoCodec.H264
        val mime = codec.mimeType ?: return false
        return report.support(mime).canEncode
    }

    fun suggestedExportFileName(): String {
        val base = mutableUiState.value.mediaName
            ?.substringBeforeLast('.')
            ?.sanitizeForFileName()
            ?.take(48)
            ?: "CC-Player"
        val codec = mutableUiState.value.export.selectedCodec.name.lowercase()
        return "$base-$codec-graded.mp4"
    }

    fun startExport(destinationUri: Uri) {
        val input = currentUri ?: return
        val state = mutableUiState.value
        if (state.mediaKind != MediaKind.VIDEO || exportEngine.isRunning) return
        val codec = state.export.selectedCodec
        if (!isExportCodecEnabled(codec)) {
            mutableUiState.update {
                it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = "No ${codec.displayName} encoder was found on this device"))
            }
            return
        }

        playbackEngine.pause()
        exportDestinationUri = destinationUri
        val temporary = File(
            getApplication<Application>().cacheDir,
            "exports/${UUID.randomUUID()}.mp4",
        )
        exportTemporaryFile = temporary
        mutableUiState.update {
            it.copy(
                showExportSheet = false,
                export = it.export.copy(status = ExportStatus.RUNNING, progress = null, message = null),
            )
        }

        exportEngine.start(
            input = input,
            destination = temporary,
            codec = codec,
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

    private fun copyExportToDestination(outputFile: File) {
        val destination = exportDestinationUri
        if (destination == null) {
            mutableUiState.update {
                it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = "Missing export destination"))
            }
            return
        }
        exportJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(destination, "w")
                        ?: throw IOException("Could not open export destination")
                    output.buffered().use { target ->
                        outputFile.inputStream().buffered().use { source -> source.copyTo(target) }
                    }
                }
            }.onSuccess {
                mutableUiState.update {
                    it.copy(
                        export = it.export.copy(
                            status = ExportStatus.SUCCESS,
                            progress = 100,
                            message = "Export saved with the same color effect used in preview",
                        ),
                    )
                }
            }.onFailure { error ->
                mutableUiState.update {
                    it.copy(export = it.export.copy(status = ExportStatus.ERROR, message = error.message))
                }
            }
            outputFile.delete()
            exportTemporaryFile = null
        }
    }

    fun cancelExport() {
        exportEngine.cancel()
        exportJob?.cancel()
        exportTemporaryFile?.delete()
        exportTemporaryFile = null
        exportDestinationUri?.let { uri ->
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
        }
        mutableUiState.update { it.copy(export = ExportUiState(selectedCodec = it.export.selectedCodec)) }
    }

    fun dismissExportResult() {
        mutableUiState.update {
            it.copy(export = ExportUiState(selectedCodec = it.export.selectedCodec))
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
        exportJob?.cancel()
        exportEngine.cancel()
        exportTemporaryFile?.delete()
        playbackEngine.close()
        super.onCleared()
    }
}

private fun String.sanitizeForFileName(): String =
    replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifBlank { "CC-Player" }
