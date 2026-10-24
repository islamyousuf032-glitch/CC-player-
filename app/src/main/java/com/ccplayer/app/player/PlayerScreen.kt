package com.ccplayer.app.player

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ccplayer.core.media.export.ExportProfiles
import com.ccplayer.media.android.playback.PlaybackStatus
import java.util.Locale
import java.util.concurrent.TimeUnit

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openMedia)
    }
    val sequenceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::openSequence)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/*"),
    ) { uri ->
        uri?.let(viewModel::startExport)
    }
    val sequenceExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::startExport)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CC PLAYER", fontWeight = FontWeight.Bold)
                        Text(
                            "PRO MEDIA CANVAS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                navigationIcon = {
                    if (state.mediaKind != MediaKind.NONE) {
                        IconButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Rounded.Add, contentDescription = "Open another video")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showCodecReport(true) }) {
                        Icon(Icons.Rounded.Info, contentDescription = "Device codec capabilities")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (state.mediaKind) {
                MediaKind.NONE -> WelcomeScreen(
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                    recents = state.recentMedia,
                    onRecent = viewModel::openRecent,
                    onRemove = viewModel::removeRecent,
                    onOpenSequence = { sequenceLauncher.launch(null) },
                )
                MediaKind.VIDEO -> VideoWorkspace(
                    state = state,
                    viewModel = viewModel,
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                )
                MediaKind.MCRAW -> McrawWorkspace(
                    state = state,
                    viewModel = viewModel,
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                )
                MediaKind.SEQUENCE -> SequenceWorkspace(
                    state = state,
                    viewModel = viewModel,
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                )
                MediaKind.UNSUPPORTED -> UnsupportedMedia(
                    state = state,
                    onOpen = { openLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
    }

    if (state.showGradeSheet) {
        GradeSheet(
            grade = state.grade,
            onGradeChange = viewModel::setGrade,
            onDismiss = { viewModel.showGradeSheet(false) },
        )
    }
    if (state.showExportSheet) {
        ExportSheet(
            selectedProfileId = state.export.selectedProfileId,
            isProfileEnabled = viewModel::isExportProfileEnabled,
            onProfileSelected = viewModel::selectExportProfile,
            onDismiss = { viewModel.showExportSheet(false) },
            onChooseDestination = {
                if (ExportProfiles.byId(state.export.selectedProfileId).container.isImageSequence) {
                    sequenceExportLauncher.launch(null)
                } else {
                    exportLauncher.launch(viewModel.suggestedExportFileName())
                }
            },
        )
    }
    if (state.showCodecReport) {
        CodecReportDialog(
            state = state,
            onDismiss = { viewModel.showCodecReport(false) },
        )
    }
}

@Composable
private fun WelcomeScreen(
    onOpen: () -> Unit,
    recents: List<RecentMedia>,
    onRecent: (android.net.Uri) -> Unit,
    onRemove: (android.net.Uri) -> Unit,
    onOpenSequence: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Icon(
                Icons.Rounded.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(24.dp).size(52.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "A precise canvas\nfor every frame.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Hardware-first playback, native RAW foundations and one shared color pipeline for preview and export.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open video or MCRAW")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenSequence, modifier = Modifier.fillMaxWidth()) {
            Text("Open EXR / DPX / PNG image sequence")
        }
        Spacer(Modifier.height(18.dp))
        FoundationCard()
        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("RECENT FILES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    recents.forEach { recent ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onRecent(recent.uri) }, modifier = Modifier.weight(1f)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(recent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (recent.positionMs > 0) {
                                        Text("Resume ${formatTime(recent.positionMs)}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            IconButton(onClick = { onRemove(recent.uri) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove ${recent.name} from recents")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "No account • no cloud upload • no broad storage permission",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FoundationCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("WORKING FOUNDATION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            CapabilityRow("Hybrid native playback", "Media3 hardware decoding with clocked FFmpeg fallback for ProRes, APV, DNxHR and broad containers")
            CapabilityRow("Native MCRAW", "Container metadata plus draft RAW frame preview and scrubbing")
            CapabilityRow("Color + professional export", "Shared linear-light grade with verified ProRes, DNxHR, FFV1, APV and hardware MP4 export")
            CapabilityRow("Exact native output", "Encoder profile, pixel format, bit depth, audio path and decoded frame count are verified")
        }
    }
}

@Composable
private fun CapabilityRow(title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@UnstableApi
@Composable
private fun VideoWorkspace(state: PlayerUiState, viewModel: PlayerViewModel, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        MediaHeader(
            state.mediaName.orEmpty(),
            when (state.playbackBackend) {
                PlaybackBackend.FFMPEG -> "FFMPEG • NATIVE CLOCK + LINEAR COLOR"
                PlaybackBackend.MEDIA3 -> "MEDIA3 • HARDWARE FIRST"
                PlaybackBackend.NONE -> "OPENING MEDIA",
            },
        )
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            EngineVideoSurface(
                engine = viewModel.engine,
                aspectRatio = state.playback.aspectRatio,
                resizeMode = state.resizeMode,
                keepScreenOn = state.playback.isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.playback.status == PlaybackStatus.LOADING) {
                CircularProgressIndicator()
            }
            if (state.playback.status == PlaybackStatus.ERROR) {
                ErrorOverlay(state.playback.errorMessage.orEmpty(), onOpen)
            }
        }
        ExportStatusBanner(state, viewModel)
        PlaybackControls(state, viewModel)
    }
}

@Composable
private fun MediaHeader(name: String, badge: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text("This stream needs another decoder", fontWeight = FontWeight.Bold)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpen) { Text("Choose another file") }
        }
    }
}

@Composable
private fun PlaybackControls(state: PlayerUiState, viewModel: PlayerViewModel) {
    val playback = state.playback
    var scrubPosition by remember(state.mediaName) { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(0)

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Slider(
            value = scrubPosition ?: playback.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { position ->
                if (scrubPosition == null) viewModel.beginScrubbing()
                scrubPosition = position
                viewModel.scrubTo(position.toLong())
            },
            onValueChangeFinished = {
                scrubPosition?.let { viewModel.endScrubbing(it.toLong()) }
                scrubPosition = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = duration > 0 && playback.status != PlaybackStatus.ERROR,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(scrubPosition?.toLong() ?: playback.positionMs), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.seekBy(-10_000) }, enabled = duration > 0) {
                Icon(Icons.Rounded.FastRewind, contentDescription = "Back 10 seconds")
            }
            Spacer(Modifier.width(20.dp))
            FilledIconButton(onClick = viewModel::playPause, modifier = Modifier.size(56.dp), enabled = playback.status != PlaybackStatus.ERROR) {
                Icon(
                    if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playback.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(20.dp))
            IconButton(onClick = { viewModel.seekBy(10_000) }, enabled = duration > 0) {
                Icon(Icons.Rounded.FastForward, contentDescription = "Forward 10 seconds")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.showGradeSheet(true) }) { Text("Grade") }
            TextButton(onClick = viewModel::cycleSpeed) { Text(String.format(Locale.ROOT, "%.2g×", playback.speed)) }
            TextButton(onClick = viewModel::toggleRepeat) {
                Text(if (playback.repeat) "Repeat on" else "Repeat", color = if (playback.repeat) MaterialTheme.colorScheme.primary else Color.Unspecified)
            }
            TextButton(onClick = viewModel::cycleResizeMode) { Text(state.resizeMode.name.lowercase()) }
            TextButton(onClick = { viewModel.showExportSheet(true) }, enabled = playback.status != PlaybackStatus.ERROR) { Text("Export") }
        }
    }
}

@Composable
private fun ExportStatusBanner(state: PlayerUiState, viewModel: PlayerViewModel) {
    val export = state.export
    if (export.status == ExportStatus.IDLE) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (export.status) {
                        ExportStatus.RUNNING -> "Rendering on device"
                        ExportStatus.COPYING -> "Saving to destination"
                        ExportStatus.SUCCESS -> "Export complete"
                        ExportStatus.ERROR -> "Export needs attention"
                        ExportStatus.IDLE -> ""
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (export.status == ExportStatus.RUNNING || export.status == ExportStatus.COPYING) {
                    TextButton(onClick = viewModel::cancelExport) { Text("Cancel") }
                } else {
                    IconButton(onClick = viewModel::dismissExportResult) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss export status")
                    }
                }
            }
            if (export.status == ExportStatus.RUNNING || export.status == ExportStatus.COPYING) {
                val progress = export.progress
                if (progress == null || export.status == ExportStatus.COPYING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (export.status == ExportStatus.RUNNING) {
                export.etaSeconds?.let { eta ->
                    Text("About ${formatTime(eta * 1000)} remaining", style = MaterialTheme.typography.labelSmall)
                }
            }
            export.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun McrawWorkspace(state: PlayerUiState, viewModel: PlayerViewModel, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        MediaHeader(state.mediaName.orEmpty(), "NATIVE MCRAW • CLOCKED DRAFT + PCM")
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            state.mcrawPreview?.let { bitmap ->
                PreviewImage(bitmap = bitmap)
            }
            if (state.mcrawPreview == null && state.mcrawPlaybackError == null) CircularProgressIndicator()
            state.mcrawPlaybackError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp), textAlign = TextAlign.Center)
            }
            if (state.mcrawPreview != null && state.isBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val info = state.mcrawInfo
            if (info != null) {
                Text(
                    "${info.width} × ${info.height}  •  ${info.frameCount} frames  •  ${info.sensorArrangement.uppercase(Locale.ROOT)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Audio ${info.audioChannels} ch / ${info.audioSampleRateHz} Hz  •  Gyro ${if (info.hasGyro) "yes" else "no"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = viewModel::playMcrawPause) {
                        Icon(
                            if (state.mcrawPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.mcrawPlaying) "Pause MCRAW" else "Play MCRAW",
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${formatTime(state.mcrawPositionUs / 1000)} / ${formatTime(state.mcrawDurationUs / 1000)}  •  dropped ${state.mcrawDroppedFrames}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (info.frameCount > 1) {
                    var pendingFrame by remember(info) { mutableStateOf<Int?>(null) }
                    Slider(
                        value = (pendingFrame ?: state.mcrawFrameIndex).toFloat(),
                        onValueChange = { pendingFrame = it.toInt() },
                        onValueChangeFinished = {
                            pendingFrame?.let(viewModel::renderMcrawFrame)
                            pendingFrame = null
                        },
                        valueRange = 0f..(info.frameCount - 1).toFloat(),
                        steps = (info.frameCount - 2).coerceIn(0, 100),
                    )
                    Text("Frame ${(pendingFrame ?: state.mcrawFrameIndex) + 1} / ${info.frameCount}", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "Persistent native decoding, exact frame timestamps, PCM audio and one-frame read-ahead. Draft CPU demosaic/white balance is not the final camera color or GPU quality stage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open another file") }
        }
    }
}

@Composable
private fun SequenceWorkspace(state: PlayerUiState, viewModel: PlayerViewModel, onOpen: () -> Unit) {
    val playback = state.sequencePlayback
    Column(Modifier.fillMaxSize()) {
        MediaHeader(state.mediaName.orEmpty(), "FFMPEG IMAGE SEQUENCE • RATIONAL FPS")
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            playback.preview?.let { PreviewImage(it) }
            if (playback.preview == null && playback.error == null) CircularProgressIndicator()
            playback.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp), textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = viewModel::playSequencePause) {
                    Icon(
                        if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause sequence" else "Play sequence",
                    )
                }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = viewModel::cycleSequenceFrameRate) {
                    Text("${String.format(Locale.ROOT, "%.3f", playback.frameRateNum.toDouble() / playback.frameRateDen)} fps")
                }
                Text(
                    "${formatTime(playback.positionUs / 1000)} / ${formatTime(playback.durationUs / 1000)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (playback.frameCount > 1) {
                var pendingFrame by remember(state.sequenceInfo) { mutableStateOf<Int?>(null) }
                Slider(
                    value = (pendingFrame ?: playback.frameIndex).toFloat(),
                    onValueChange = { pendingFrame = it.toInt() },
                    onValueChangeFinished = {
                        pendingFrame?.let(viewModel::renderSequenceFrame)
                        pendingFrame = null
                    },
                    valueRange = 0f..(playback.frameCount - 1).toFloat(),
                    steps = (playback.frameCount - 2).coerceIn(0, 100),
                )
                Text("Frame ${(pendingFrame ?: playback.frameIndex) + 1} / ${playback.frameCount} • dropped ${playback.droppedFrames}")
            }
            state.sequenceInfo?.let { info ->
                Text(
                    if (info.gapCount > 0) "${info.gapCount} numeric frame gaps detected; timestamps remain contiguous by explicit policy" else "Natural numeric sorting • no detected frame-number gaps",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.gapCount > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Native FFmpeg frame decode with one-frame read-ahead. Sequences have no embedded audio; decode rate remains device/source dependent.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open another file") }
        }
    }
}

@Composable
private fun PreviewImage(bitmap: Bitmap) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Decoded video frame",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun UnsupportedMedia(state: PlayerUiState, onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(16.dp))
        Text(state.mediaName.orEmpty(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            state.unsupportedDetail ?: "This format needs a backend that is not connected yet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpen) { Text("Choose another file") }
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds.coerceAtLeast(0))
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
