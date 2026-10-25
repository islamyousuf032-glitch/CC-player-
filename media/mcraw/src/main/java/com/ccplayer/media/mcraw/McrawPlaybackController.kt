package com.ccplayer.media.mcraw

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.max

data class McrawPlaybackState(
    val isOpen: Boolean = false,
    val isPlaying: Boolean = false,
    val ended: Boolean = false,
    val frameIndex: Int = 0,
    val frameCount: Int = 0,
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val preview: Bitmap? = null,
    val quality: McrawRenderQuality = McrawRenderQuality.DRAFT,
    val droppedFrames: Int = 0,
    val error: String? = null,
)

/** Clocked RAW draft playback with one-frame read-ahead and timestamped native PCM chunks. */
class McrawPlaybackController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val videoDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val audioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mutableState = MutableStateFlow(McrawPlaybackState())
    val state: StateFlow<McrawPlaybackState> = mutableState.asStateFlow()

    private var uri: Uri? = null
    private var videoSession: McrawSession? = null
    private var audioSession: McrawSession? = null
    private var timestampsUs = LongArray(0)
    private var firstTimestampUs = 0L
    private var frameDurationUs = 40000L
    private var sampleRate = 0
    private var channels = 0
    private var openJob: Job? = null
    private var playbackJobs = emptyList<Job>()
    private var generation = 0L
    @Volatile private var playing = false
    @Volatile private var basePositionUs = 0L
    @Volatile private var anchorNanoTime = 0L
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioAnchorMediaUs = -1L
    @Volatile private var audioAnchorFrame = 0L

    fun open(source: Uri, info: McrawInfo, startPositionUs: Long = 0) {
        openJob?.cancel()
        stopPlayback()
        mutableState.value = McrawPlaybackState()
        openJob = scope.launch {
            runCatching {
                closeSessions()
                uri = source
                sampleRate = info.audioSampleRateHz
                channels = info.audioChannels
                val session = withContext(videoDispatcher) { McrawSession(appContext, source) }
                videoSession = session
                val timestamps = withContext(videoDispatcher) {
                    LongArray(session.frameCount) { index -> session.frameTimestampUs(index) }
                }
                require(timestamps.isNotEmpty()) { "MCRAW contains no frames" }
                timestampsUs = timestamps
                firstTimestampUs = timestamps.first()
                frameDurationUs = if (timestamps.size > 1) {
                    ((timestamps.last() - timestamps.first()) / (timestamps.size - 1)).coerceAtLeast(1)
                } else 40000
                val duration = if (timestamps.size > 1) timestamps.last() - timestamps.first() + frameDurationUs else frameDurationUs
                val startIndex = frameIndexForPosition(startPositionUs.coerceIn(0, duration))
                val resumedPosition = (timestamps[startIndex] - firstTimestampUs).coerceAtLeast(0)
                basePositionUs = resumedPosition
                mutableState.value = McrawPlaybackState(
                    isOpen = true,
                    frameIndex = startIndex,
                    frameCount = timestamps.size,
                    positionUs = resumedPosition,
                    durationUs = duration,
                )
                renderStill(startIndex, generation)
            }.onFailure { error ->
                mutableState.value = McrawPlaybackState(error = error.message ?: "MCRAW playback could not open")
            }
        }
    }

    fun playPause() {
        if (playing) pause() else play()
    }

    fun cycleQuality() {
        val quality = if (mutableState.value.quality == McrawRenderQuality.DRAFT) {
            McrawRenderQuality.BILINEAR_CPU
        } else {
            McrawRenderQuality.DRAFT
        }
        mutableState.value = mutableState.value.copy(quality = quality)
        if (!playing && mutableState.value.isOpen) seekFrame(mutableState.value.frameIndex, resume = false)
    }

    fun play() {
        if (!mutableState.value.isOpen || playing) return
        if (mutableState.value.ended) seekFrame(0, resume = false)
        basePositionUs = if (mutableState.value.ended) 0 else mutableState.value.positionUs
        anchorNanoTime = System.nanoTime()
        playing = true
        mutableState.value = mutableState.value.copy(isPlaying = true, ended = false, error = null)
        startPlaybackJobs()
    }

    fun pause() {
        if (!playing) return
        val position = clockPositionUs()
        stopPlayback()
        basePositionUs = position
        mutableState.value = mutableState.value.copy(isPlaying = false, positionUs = position)
    }

    fun seekFrame(frameIndex: Int, resume: Boolean = playing) {
        val count = mutableState.value.frameCount
        if (count <= 0) return
        val target = frameIndex.coerceIn(0, count - 1)
        stopPlayback()
        val position = (timestampsUs[target] - firstTimestampUs).coerceAtLeast(0)
        basePositionUs = position
        mutableState.value = mutableState.value.copy(
            frameIndex = target,
            positionUs = position,
            ended = false,
            error = null,
        )
        val expected = generation
        openJob = scope.launch {
            renderStill(target, expected)
            if (resume && generation == expected) play()
        }
    }

    private fun startPlaybackJobs() {
        val expected = generation
        val video = scope.launch { videoLoop(expected) }
        val ticker = scope.launch {
            while (isActive && generation == expected && playing) {
                mutableState.value = mutableState.value.copy(positionUs = clockPositionUs())
                delay(100)
            }
        }
        val jobs = mutableListOf(video, ticker)
        if (sampleRate > 0 && channels > 0) jobs += scope.launch { audioLoop(expected) }
        playbackJobs = jobs
    }

    private suspend fun renderStill(index: Int, expected: Long) {
        val session = videoSession ?: return
        runCatching {
            val quality = mutableState.value.quality
            withContext(videoDispatcher) { session.renderDraft(index, PREVIEW_DIMENSION, quality) }
        }.onSuccess { bitmap ->
            if (generation == expected) {
                mutableState.value = mutableState.value.copy(preview = bitmap, frameIndex = index, error = null)
            }
        }.onFailure { error ->
            if (generation == expected) mutableState.value = mutableState.value.copy(error = error.message)
        }
    }

    private suspend fun videoLoop(expected: Long) {
        val session = videoSession ?: return
        var nextIndex = frameIndexForPosition(clockPositionUs())
        var prefetch: Deferred<Bitmap>? = null
        try {
            while (coroutineContext.isActive && generation == expected && playing) {
                if (nextIndex >= timestampsUs.size) {
                    playing = false
                    basePositionUs = mutableState.value.durationUs
                    mutableState.value = mutableState.value.copy(
                        isPlaying = false,
                        ended = true,
                        positionUs = mutableState.value.durationUs,
                        frameIndex = (timestampsUs.size - 1).coerceAtLeast(0),
                    )
                    break
                }
                val relativePts = timestampsUs[nextIndex] - firstTimestampUs
                val clock = clockPositionUs()
                if (relativePts < clock - frameDurationUs * 2 && nextIndex < timestampsUs.lastIndex) {
                    nextIndex++
                    mutableState.value = mutableState.value.copy(droppedFrames = mutableState.value.droppedFrames + 1)
                    continue
                }
                if (relativePts > clock + 2000) {
                    delay(((relativePts - clock) / 1000).coerceIn(1, 20))
                    continue
                }
                if (prefetch == null) {
                    val index = nextIndex
                    val quality = mutableState.value.quality
                    prefetch = scope.async(videoDispatcher) { session.renderDraft(index, PREVIEW_DIMENSION, quality) }
                }
                val bitmap = prefetch!!.await()
                prefetch = null
                if (generation != expected || !playing) break
                mutableState.value = mutableState.value.copy(
                    preview = bitmap,
                    frameIndex = nextIndex,
                    positionUs = clockPositionUs(),
                    error = null,
                )
                nextIndex++
                if (nextIndex < timestampsUs.size) {
                    val index = nextIndex
                    val quality = mutableState.value.quality
                    prefetch = scope.async(videoDispatcher) { session.renderDraft(index, PREVIEW_DIMENSION, quality) }
                }
            }
        } catch (error: Throwable) {
            if (generation == expected) {
                playing = false
                mutableState.value = mutableState.value.copy(isPlaying = false, error = error.message)
            }
        } finally {
            prefetch?.cancel()
        }
    }

    private suspend fun audioLoop(expected: Long) {
        try {
            val source = uri ?: return
            val session = withContext(audioDispatcher) {
                audioSession?.close()
                McrawSession(appContext, source).also { audioSession = it }
            }
            val track = createAudioTrack()
            audioTrack = track
            track.play()
            var inferredTimestampUs = firstTimestampUs
            while (coroutineContext.isActive && generation == expected && playing) {
                val chunk = withContext(audioDispatcher) { session.nextAudioChunk() } ?: break
                val sampleFrames = chunk.samples.size / channels
                if (sampleFrames <= 0) continue
                var timestamp = if (chunk.timestampUs >= 0) chunk.timestampUs else inferredTimestampUs
                inferredTimestampUs = timestamp + sampleFrames * 1_000_000L / sampleRate
                var relative = timestamp - firstTimestampUs
                var firstFrame = 0
                val target = basePositionUs
                if (relative + sampleFrames * 1_000_000L / sampleRate <= target) continue
                if (relative < target) {
                    firstFrame = (((target - relative) * sampleRate + 999_999) / 1_000_000L).toInt().coerceIn(0, sampleFrames)
                    relative += firstFrame * 1_000_000L / sampleRate
                }
                if (audioAnchorMediaUs < 0) {
                    audioAnchorMediaUs = relative
                    audioAnchorFrame = track.playbackHeadPosition.toLong()
                }
                var offset = firstFrame * channels
                val end = chunk.samples.size
                while (offset < end && generation == expected && playing) {
                    val written = track.write(chunk.samples, offset, end - offset, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) throw IllegalStateException("MCRAW AudioTrack write failed: $written")
                    if (written == 0) delay(1) else offset += written
                }
            }
        } catch (error: Throwable) {
            if (generation == expected) {
                mutableState.value = mutableState.value.copy(error = "MCRAW audio: ${error.message}")
            }
        }
    }

    private fun createAudioTrack(): AudioTrack {
        releaseAudioTrack()
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_SURROUND
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> throw IllegalArgumentException("Unsupported MCRAW audio channel count: $channels")
        }
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "MCRAW audio format is not supported by AudioTrack" }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(mask).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimum * 2, sampleRate * channels * 2 / 5))
            .build()
    }

    private fun clockPositionUs(): Long {
        if (!playing) return basePositionUs
        val track = audioTrack
        val anchor = audioAnchorMediaUs
        if (track != null && anchor >= 0) {
            val timestamp = AudioTimestamp()
            if (track.getTimestamp(timestamp)) {
                val frames = timestamp.framePosition - audioAnchorFrame
                if (frames >= 0) return (anchor + frames * 1_000_000L / sampleRate).coerceIn(0, mutableState.value.durationUs)
            }
        }
        return (basePositionUs + (System.nanoTime() - anchorNanoTime) / 1000).coerceIn(0, mutableState.value.durationUs)
    }

    private fun frameIndexForPosition(positionUs: Long): Int {
        if (timestampsUs.isEmpty()) return 0
        var low = 0
        var high = timestampsUs.lastIndex
        var result = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            val relative = timestampsUs[middle] - firstTimestampUs
            if (relative <= positionUs) {
                result = middle
                low = middle + 1
            } else high = middle - 1
        }
        return result
    }

    private fun stopPlayback() {
        generation++
        playing = false
        playbackJobs.forEach { it.cancel() }
        playbackJobs = emptyList()
        releaseAudioTrack()
    }

    private fun releaseAudioTrack() {
        audioAnchorMediaUs = -1
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private suspend fun closeSessions() {
        withContext(videoDispatcher) {
            videoSession?.close()
            videoSession = null
        }
        withContext(audioDispatcher) {
            audioSession?.close()
            audioSession = null
        }
    }

    override fun close() {
        openJob?.cancel()
        stopPlayback()
        scope.cancel()
        videoSession?.close()
        audioSession?.close()
        videoSession = null
        audioSession = null
        videoDispatcher.close()
        audioDispatcher.close()
    }

    private companion object { const val PREVIEW_DIMENSION = 640 }
}
